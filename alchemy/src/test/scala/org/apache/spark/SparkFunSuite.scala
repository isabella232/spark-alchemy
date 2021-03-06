package org.apache.spark

// scalastyle:off
import java.io.File

import scala.annotation.tailrec
import org.apache.log4j.{Appender, Level, Logger}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, BeforeAndAfterEach, Outcome, Suite}
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.Tests.IS_TESTING
import org.apache.spark.util.{AccumulatorContext, Utils}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Base abstract class for all unit tests in Spark for handling common functionality.
  *
  * Thread audit happens normally here automatically when a new test suite created.
  * The only prerequisite for that is that the test class must extend [[SparkFunSuite]].
  *
  * It is possible to override the default thread audit behavior by setting enableAutoThreadAudit
  * to false and manually calling the audit methods, if desired. For example:
  *
  * class MyTestSuite extends SparkFunSuite {
  *
  *   override val enableAutoThreadAudit = false
  *
  *   protected override def beforeAll(): Unit = {
  *     doThreadPreAudit()
  *     super.beforeAll()
  *   }
  *
  *   protected override def afterAll(): Unit = {
  *     super.afterAll()
  *     doThreadPostAudit()
  *   }
  * }
  */
abstract class SparkFunSuite
  extends AnyFunSuite
    with SparkSuiteBase {
  // scalastyle:on

  /**
    * Note: this method doesn't support `BeforeAndAfter`. You must use `BeforeAndAfterEach` to
    * set up and tear down resources.
    */
  def testRetry(s: String, n: Int = 2)(body: => Unit): Unit = {
    test(s) {
      retry(n) {
        body
      }
    }
  }

  /**
    * Log the suite name and the test name before and after each test.
    *
    * Subclasses should never override this method. If they wish to run
    * custom code before and after each test, they should mix in the
    * {{org.scalatest.BeforeAndAfter}} trait instead.
    */
  final protected override def withFixture(test: NoArgTest): Outcome = {
    val testName = test.text
    val suiteName = this.getClass.getName
    val shortSuiteName = suiteName.replaceAll("org.apache.spark", "o.a.s")
    try {
      logInfo(s"\n\n===== TEST OUTPUT FOR $shortSuiteName: '$testName' =====\n")
      test()
    } finally {
      logInfo(s"\n\n===== FINISHED $shortSuiteName: '$testName' =====\n")
    }
  }

}


trait SparkSuiteBase
  extends BeforeAndAfterAll
    with BeforeAndAfterEach
    with Logging {
  // scalastyle:on
  this: Suite =>

  protected override def beforeAll(): Unit = {
    System.setProperty(IS_TESTING.key, "true")
    super.beforeAll()
  }

  protected override def afterAll(): Unit = {
    try {
      // Avoid leaking map entries in tests that use accumulators without SparkContext
      AccumulatorContext.clear()
    } finally {
      super.afterAll()
    }
  }

  // helper function
  protected final def getTestResourceFile(file: String): File = {
    new File(getClass.getClassLoader.getResource(file).getFile)
  }

  protected final def getTestResourcePath(file: String): String = {
    getTestResourceFile(file).getCanonicalPath
  }

  /**
    * Note: this method doesn't support `BeforeAndAfter`. You must use `BeforeAndAfterEach` to
    * set up and tear down resources.
    */
  def retry[T](n: Int)(body: => T): T = {
    if (this.isInstanceOf[BeforeAndAfter]) {
      throw new UnsupportedOperationException(
        s"testRetry/retry cannot be used with ${classOf[BeforeAndAfter]}. " +
          s"Please use ${classOf[BeforeAndAfterEach]} instead.")
    }
    retry0(n, n)(body)
  }

  @tailrec private final def retry0[T](n: Int, n0: Int)(body: => T): T = {
    try body
    catch { case e: Throwable =>
      if (n > 0) {
        logWarning(e.getMessage, e)
        logInfo(s"\n\n===== RETRY #${n0 - n + 1} =====\n")
        // Reset state before re-attempting in order so that tests which use patterns like
        // LocalSparkContext to clean up state can work correctly when retried.
        afterEach()
        beforeEach()
        retry0(n-1, n0)(body)
      }
      else throw e
    }
  }

  /**
    * Creates a temporary directory, which is then passed to `f` and will be deleted after `f`
    * returns.
    */
  protected def withTempDir(f: File => Unit): Unit = {
    val dir = Utils.createTempDir()
    try f(dir) finally {
      Utils.deleteRecursively(dir)
    }
  }

  /**
    * Adds a log appender and optionally sets a log level to the root logger or the logger with
    * the specified name, then executes the specified function, and in the end removes the log
    * appender and restores the log level if necessary.
    */
  protected def withLogAppender(
    appender: Appender,
    loggerName: Option[String] = None,
    level: Option[Level] = None)(
    f: => Unit): Unit = {
    val logger = loggerName.map(Logger.getLogger).getOrElse(Logger.getRootLogger)
    val restoreLevel = logger.getLevel
    logger.addAppender(appender)
    if (level.isDefined) {
      logger.setLevel(level.get)
    }
    try f finally {
      logger.removeAppender(appender)
      if (level.isDefined) {
        logger.setLevel(restoreLevel)
      }
    }
  }
}

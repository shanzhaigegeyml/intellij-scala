package org.jetbrains.plugins.scala.failed.typeInference

import org.jetbrains.plugins.scala.PerfCycleTests
import org.jetbrains.plugins.scala.lang.typeInference.TypeInferenceTestBase
import org.junit.experimental.categories.Category

/**
  * Created by mucianm on 22.03.16.
  */
@Category(Array(classOf[PerfCycleTests]))
class PatternsTest extends TypeInferenceTestBase {
  override def folderPath: String = super.folderPath + "bugs5/"

  def testSCL4500(): Unit = doTest()

  def testSCL9137(): Unit = doTest()

  def testSCL9888():Unit = doTest()

  def testSCL8171(): Unit = {
    val text =
      s"""import scala.collection.immutable.NumericRange
          |
          |val seq = Seq("")
          |val x = seq match {
          |  case nr: NumericRange[_] => ${START}nr$END
          |  case _ => null
          |}
          |//NumericRange[_]""".stripMargin
    doTest(text)
  }

  // something seriously wrong, y isn't even a valid psi
  def testSCL4989(): Unit = {
    doTest(
      s"""
        |val x: Product2[Int, Int] = (10, 11)
        |val (y, _) = x
        |${START}y$END
        |//Int
      """.stripMargin)
  }

  def testSCL4487(): Unit = {
    doTest(
      s"""
         |def x(a: Int): String => Int = _ match {
         |  case value if value == "0" => ${START}a$END
         |}
         |//(String) => Int
      """.stripMargin)
  }

  def testSCL9241(): Unit = {
    doTest(
      s"""
         |trait Inv[A] { def head: A }
         |trait Cov[+A] { def head: A }
         |
         |def inv(i: Inv[Inv[String]]) = i match {
         |    case l: Inv[a] =>
         |      val x: a = ${START}l.head$END
         |  }
         |//a
      """.stripMargin)
  }

}

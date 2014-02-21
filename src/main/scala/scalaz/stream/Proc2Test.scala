package scalaz.stream

import Proc2._
import scalaz.concurrent.Task

object Proc2Test extends App {

  def time(a: => Any): Unit = {
    val start = System.currentTimeMillis
    val result = a
    val stop = System.currentTimeMillis
    // println("result: " + result)
    println(s"took ${(stop-start)/1000.0} seconds")
  }

  def bad(n: Int): Proc2[Task,Int] =
    (0 to n).map(emit).foldLeft(halt: Proc2[Nothing,Int])(_ ++ _)

  def good(n: Int): Proc2[Task,Int] =
    (0 to n).map(emit).reverse.foldLeft(halt: Proc2[Nothing,Int])(
      (acc,h) => h ++ acc
    )

  println("bad")
  time { bad(1).runLog.run }
  time { bad(10).runLog.run }
  time { bad(100).runLog.run }
  time { bad(200).runLog.run }
  time { bad(300).runLog.run }
  time { bad(400).runLog.run }
  time { bad(500).runLog.run }

  println("bad")
  time { good(1).runLog.run }
  time { good(10).runLog.run }
  time { good(100).runLog.run }
  time { good(200).runLog.run }
  time { good(300).runLog.run }
  time { good(400).runLog.run }
  time { good(500).runLog.run }
}

package scalaz.stream

import Proc3._
import scalaz.concurrent.Task
import scalaz.Monoid

object Proc3Test extends App {

  def time(a: => Any): Unit = {
    val start = System.currentTimeMillis
    val result = a
    val stop = System.currentTimeMillis
    // println("result: " + result)
    println(s"took ${(stop-start)/1000.0} seconds")
  }

  def bad(n: Int): Proc3[Task,Int] =
    (0 to n).map(emit).foldLeft(halt: Proc3[Nothing,Int])(_ ++ _)

  def good(n: Int): Proc3[Task,Int] =
    (0 to n).map(emit).reverse.foldLeft(halt: Proc3[Nothing,Int])(
      (acc,h) => h ++ acc
    )

  def badFlatMap(n: Int): Proc3[Task,Int] =
    if (n == 0) halt
    else emit(1).flatMap(_ => badFlatMap(n-1) ++ badFlatMap(n-1))

  implicit val B = Monoid.instance[Int]((a,b) => a+b, 0)

  println("bad")
  time { bad(1).runFoldMap(identity).run }
  time { bad(10).runFoldMap(identity).run }
  time { bad(100).runFoldMap(identity).run }
  time { bad(1000).runFoldMap(identity).run }
  time { bad(10000).runFoldMap(identity).run }
  time { bad(100000).runFoldMap(identity).run }
  time { bad(1000000).runFoldMap(identity).run }

  println("good")
  time { good(1).runFoldMap(identity).run }
  time { good(10).runFoldMap(identity).run }
  time { good(100).runFoldMap(identity).run }
  time { good(1000).runFoldMap(identity).run }
  time { good(10000).runFoldMap(identity).run }
  time { good(100000).runFoldMap(identity).run }
  time { good(1000000).runFoldMap(identity).run }

  //println("badFlatMap")
  //time { badFlatMap(8).runFoldMap(identity).run }
  //time { badFlatMap(9).runFoldMap(identity).run }
  //time { badFlatMap(10).runFoldMap(identity).run }
  //time { badFlatMap(11).runFoldMap(identity).run }
  //time { badFlatMap(12).runFoldMap(identity).run }
  //time { badFlatMap(13).runFoldMap(identity).run }
  //time { badFlatMap(14).runFoldMap(identity).run }
}


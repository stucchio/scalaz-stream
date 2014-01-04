package scalaz.stream

import Process._
import org.scalacheck.Prop._
import org.scalacheck.Properties
import scalaz.concurrent.Task


object ProcessPerformanceSpec extends Properties("Process.performance") {

  def timed[A](f: => A): (A, Long) = {
    val now = System.currentTimeMillis()
    val rf = f
    (rf, System.currentTimeMillis() - now)
  }


  //tests that additional flatMap does not trigger quadratic performance and that
  //await1.flatMap behaves similarly in performance like receive1
  property("receive1-await1") = secure {
    def badId[A]: Process1[A, A] =
      Process.await1[A].flatMap { (a: A) => emit(a) fby badId }

    def goodId[A]: Process1[A, A] =
      Process.receive1((a: A) => emit(a) fby goodId)

    val bad =
      Seq(
        timed(Process.range(0, 1000).pipe(badId).run.run)
        , timed(Process.range(0, 10000).pipe(badId).run.run)
        , timed(Process.range(0, 100000).pipe(badId).run.run)
      ).map(_._2)

    val good =
      Seq(
        timed(Process.range(0, 1000).pipe(goodId).run.run)
        , timed(Process.range(0, 10000).pipe(goodId).run.run)
        , timed(Process.range(0, 100000).pipe(goodId).run.run)
      ).map(_._2)


    s"await1: $bad" |: ((bad(0) * 10 > bad(1)) && (bad(1) * 10 > bad(2))) &&
      (s"receive1: $good" |: ((good(0) * 10 > good(1)) && (good(1) * 10 > good(2))))


  }

  //checks flatMap does not have O^2 performance
  property("flatMap") = secure {

    val fm =
      Seq(
        timed(Process.range(0, 1000).flatMap(emit).run.run)
        , timed(Process.range(0, 10000).flatMap(emit).run.run)
        , timed(Process.range(0, 100000).flatMap(emit).run.run)
      ).map(_._2)


    ((fm(0) * 10 > fm(1)) && (fm(1) * 10 > fm(2)))

  }

  //checks performance of append
  property("append") = secure {

    val apnd =
      Seq(
        timed((0 until 1000).foldLeft(halt: Process[Task, Int])((acc, i) => acc ++ emit(i)))
        , timed((0 until 10000).foldLeft(halt: Process[Task, Int])((acc, i) => acc ++ emit(i)))
        , timed((0 until 100000).foldLeft(halt: Process[Task, Int])((acc, i) => acc ++ emit(i)))
      ).map(_._2)


    ((apnd(0) * 10 > apnd(1)) && (apnd(1) * 10 > apnd(2)))

  }


}

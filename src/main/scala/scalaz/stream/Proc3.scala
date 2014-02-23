package scalaz.stream

import scalaz.{Catchable,Monad,Monoid}
import scalaz.{\/, -\/, \/-}
import scalaz.\/._

import Proc3._

sealed trait Proc3[+F[_],+O] {

  /**
   * Replace the `Halt` at the end of this `Process` with whatever
   * is produced by `f`.
   */
  final def onHalt[F2[x]>:F[x],O2>:O](f: Throwable => Proc3[F2,O2]): Proc3[F2,O2] =
    OnHalt(this, f)

  final def flatMap[F2[x]>:F[x],O2](f: O => Proc3[F2,O2]): Proc3[F2,O2] =
    this match {
      case Bind(base, g) => suspend { Bind(base, g andThen (_.flatMap(f))) }
      case _ => Bind(this.asInstanceOf[OHNF[F2,O]], f)
    }

  // onHalt normal form - we simply ensure that the outermost Process is not a Bind
  final def ohnf[F2[x]>:F[x],O2>:O](implicit F: Monad[F2]): F2[OHNF[F2,O2]] =
    this match {
      case Bind(a,f) => F.bind (suspendF { a.ohnf[F2,Any] }) {
        case h@Halt(_) => F.point(h)
        case Emit(emits) =>
          val procs = emits.map(f)
          if (procs.isEmpty) F.point(halt)
          else if (procs.size == 1) suspendF { f(procs.head).ohnf }
          else suspendF { procs.reverse.reduceLeft((tl,hd) => hd ++ tl).ohnf }
        case Await(req) => F.bind(req.asInstanceOf[F[Any]])(f andThen (_.ohnf[F2,O2]))
        case Suspend(force) => suspendF { force().asInstanceOf[Proc3[F2,Any]].flatMap(f).ohnf }
        case OnHalt(h0,t0) =>
          suspendF {
            val h = h0.asInstanceOf[Proc3[F2,Any]]
            val t = t0.asInstanceOf[Throwable => Proc3[F2,Any]]
            h.flatMap(f).onHalt(t andThen (_.flatMap(f))).ohnf
          }
      }
      case _ => F.point(this.asInstanceOf[OHNF[F2,O2]])
    }

  final def map[O2](f: O => O2): Proc3[F,O2] =
    flatMap(f andThen (emit))

  final def append[F2[x]>:F[x],O2>:O](p2: => Proc3[F2,O2]): Proc3[F2,O2] =
    onHalt {
      case End => p2
      case e => fail(e)
    }
  final def ++[F2[x]>:F[x],O2>:O](p2: => Proc3[F2,O2]): Proc3[F2,O2] = append(p2)

  final def runFoldMap[F2[x]>:F[x], B](f: O => B)(implicit F: Monad[F2], C: Catchable[F2], B: Monoid[B]): F2[B] =
    Proc3.runFoldMap(this: Proc3[F2,O])(f)

  /**
   * Collect the outputs of this `Process[F,O]`, given a `Monad[F]` in
   * which we can catch exceptions. This function is not tail recursive and
   * relies on the `Monad[F]` to ensure stack safety.
   */
  final def runLog[F2[x]>:F[x], O2>:O](implicit F: Monad[F2], C: Catchable[F2]): F2[IndexedSeq[O2]] =
    Proc3.runLog(this: Proc3[F2,O2])
}

object Proc3 {

  sealed trait OHNF[+F[_],+O] extends Proc3[F,O]
  case class Emit[+O](head: Seq[O]) extends OHNF[Nothing,O]
  case class Await[+F[_],O](req: F[O]) extends OHNF[F,O]
  case class Halt(cause: Throwable) extends OHNF[Nothing,Nothing]

  case class Suspend[+F[_],+O](force: () => Proc3[F,O]) extends OHNF[F,O]
  case class OnHalt[+F[_],+O](p: Proc3[F,O], f: Throwable => Proc3[F,O]) extends OHNF[F,O]
  case class Bind[+F[_],R,O](p: OHNF[F,R], f: R => Proc3[F,O]) extends Proc3[F,O]

  def suspendF[F[_],A](f: => F[A])(implicit F: Monad[F]): F[A] =
    F.bind(F.point(()))(_ => f)

  def suspend[F[_],O](p: => Proc3[F,O]): Proc3[F,O] =
    Suspend { () => p }

  val halt = Halt(End)

  def fail(err: Throwable): Proc3[Nothing,Nothing] = Halt(err)

  def emit[O](o: O): Proc3[Nothing,O] = Emit(Vector(o))

  def safely[F[_],A,B](f: A => Proc3[F,B]): A => Proc3[F,B] =
    (a: A) => try f(a) catch { case t: Throwable => fail(t) }

  def applySafe[F[_],R,A](f: R => Proc3[F,A])(r: R): Proc3[F,A] =
    try f(r)
    catch { case t: Throwable => fail(t) }

  def runFoldMap[F[_],O,B](p: Proc3[F,O])(f: O => B)(implicit F: Monad[F], C: Catchable[F], B: Monoid[B]): F[B] = {

    def finish(f: F[(Throwable,B)]): F[B] = F.bind(f) {
      case (End|Kill,b) => F.point(b)
      case (e,_) => C.fail(e)
    }

    def delay[A](a: => A): F[A] = F.bind(F.point(()))(_ => F.point(a))

    def go(p: Proc3[F,O], acc: B): F[(Throwable,B)] = F.bind(p.ohnf) {
      case Halt(e) => F.point(e -> acc)
      case Emit(emits) => F.point {
        var acc2 = acc
        try emits.asInstanceOf[Seq[O]].foreach { o =>
          val tmp = acc2
          acc2 = B.append(tmp,f(o))
        }
        catch { case e: Throwable => (e -> acc2) }
        (End -> acc2)
      }
      case Await(req) => F.map(C.attempt(req.asInstanceOf[F[O]]))(o =>
        o.fold(e => e -> acc,
               o => try End -> B.append(acc,f(o))
                    catch { case e: Throwable => e -> acc }
        )
      )
      case OnHalt(base, f) =>
        F.bind(suspendF { go(base.asInstanceOf[Proc3[F,O]], acc) }) { eacc2 =>
          go(applySafe(f.asInstanceOf[Throwable => Proc3[F,O]])(eacc2._1), eacc2._2)
        }
      case Suspend(force) =>
        suspendF { go(force().asInstanceOf[Proc3[F,O]], acc) }
    }
    finish(go(p, B.zero))
  }

  /**
   * Collect the outputs of this `Process[F,O]`, given a `Monad[F]` in
   * which we can catch exceptions. This function is not tail recursive and
   * relies on the `Monad[F]` to ensure stack safety.
   */
  def runLog[F[_],O](p: Proc3[F,O])(implicit F: Monad[F], C: Catchable[F]): F[IndexedSeq[O]] = {
    runFoldMap[F,O,IndexedSeq[O]](p)(IndexedSeq(_))(
      F, C,
      // workaround for performance bug in Vector ++
      Monoid.instance[IndexedSeq[O]](
        (a,b) => b.foldLeft(a)(_ :+ _),
        IndexedSeq.empty)
    )
  }

  /**
   * Special exception indicating normal termination due to
   * input ('upstream') termination. An `Await` may respond to an `End`
   * by switching to reads from a secondary source.
   */
  case object End extends Exception {
    override def fillInStackTrace = this
  }
  /**
   * Special exception indicating downstream termination.
   * An `Await` should respond to a `Kill` by performing
   * necessary cleanup actions, then halting.
   */
  case object Kill extends Exception {
    override def fillInStackTrace = this
  }
}

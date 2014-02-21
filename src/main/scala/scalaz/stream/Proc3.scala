package scalaz.stream

import scalaz.{Catchable,Monad,Monoid}
import scalaz.{\/, -\/, \/-}
import scalaz.\/._

/*
  data Base f a
    = Emit [a]
    | forall r. Await (f r) (Either Err r -> a)

  -- think this might do it, as long as we can write a tail-recursive
  -- loop to interpret it

  data Base f a
    = Emit [a]
    | Await (f a)
    | Halt Err

  data Proc f a
    = More (() -> Proc f a)
    | Done (Base f a)
    | forall r. OnHalt (Base f r) (Err -> Proc f a)
    | forall r. Bind (Base f r) (r -> Proc f a)
*/

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
      case OnHalt(p, g) => suspend { p.flatMap(f).onHalt(g andThen (_.flatMap(f))) }
      case _ => Bind(this.asInstanceOf[Base[F,O]], f)
    }

  final def append[F2[x]>:F[x],O2>:O](p2: => Proc3[F2,O2]): Proc3[F2,O2] =
    onHalt {
      case (End|Kill) => p2
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

  sealed trait Base[+F[_],+O] extends Proc3[F,O] {
    private[stream] def runBind[F2[x]>:F[x],O2](f: O => Proc3[F2,O2])(implicit F: Monad[F2]):
        F2[Seq[Proc3[F2,O2]]] = this match {
       case h@Halt(_) => F.point(Vector(h))
       case Await(req) => F.map(req)(f andThen (Vector(_)))
       case Emit(emits) => F.point(emits.map(f))
    }
  }
  case class Emit[+O](head: Seq[O]) extends Base[Nothing,O]
  case class Await[+F[_],O](req: F[O]) extends Base[F,O]
  case class Halt(cause: Throwable) extends Base[Nothing,Nothing]

  case class OnHalt[+F[_],+O](p: Proc3[F,O], f: Throwable => Proc3[F,O]) extends Proc3[F,O]
  case class Bind[+F[_],R,O](p: Base[F,R], f: R => Proc3[F,O]) extends Proc3[F,O]

  def suspend[F[_],O](p: => Proc3[F,O]): Proc3[F,O] =
    OnHalt[F,O](Halt(End), t => p)

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

    def suspend[A](f: => F[A]): F[A] = F.bind(F.point(()))(_ => f)
    def delay[A](a: => A): F[A] = F.bind(F.point(()))(_ => F.point(a))

    def go(p: Proc3[F,O], acc: B): F[(Throwable,B)] = p match {
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
        F.bind(suspend { go(base.asInstanceOf[Proc3[F,O]], acc) }) { eacc2 =>
          go(f.asInstanceOf[Throwable => Proc3[F,O]].apply(eacc2._1), eacc2._2)
        }
      case Bind(base, f) => ???
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

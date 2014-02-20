package scalaz.stream

import scalaz.concurrent.Task
import scalaz.\/
import scalaz.\/._

sealed trait Proc2[+F[_],+O] {
  import Proc2._

  /** Ignore all outputs of this `Process`. */
  final def drain: Proc2[F,Nothing] = this match {
    case h@Halt(_) => h
    case a@Await(_,_) => a.extend(_.drain)
    case Emit(ht) => Suspend { ht.map(_._2.drain) }
    case Suspend(p) => Suspend { p.map(_.drain) }
  }

  /** Send the `Kill` signal to the next `Await`, then ignore all outputs. */
  final def kill: Proc2[F,Nothing] = this.disconnect.drain

  /** Causes subsequent await to fail with the `Kill` exception. */
  final def disconnect: Proc2[F,O] = this match {
    case h@Halt(_) => h
    case a@Await(_,recv) => Suspend { recv(left(Kill)) }
    case Suspend(p) => Suspend { p.map(_.disconnect) }
    case Emit(ht) => Suspend { ht.map(_._2.disconnect) }
  }

  /**
   * Replace the `Halt` at the end of this `Process` with whatever
   * is produced by `f`.
   */
  final def onHalt[F2[x]>:F[x],O2>:O](f: Throwable => Proc2[F2,O2]): Proc2[F2,O2] =
    this match {
      case h@Halt(e) => Try(f(e))
      case Suspend(p) => Suspend(p.map(_.onHalt(f)))
      case Emit(ht) => Emit(ht.map { case (h,t) => (h, t.onHalt(f)) })
      case a@Await(_,_) => a.extend(_.onHalt(f))
    }

  final def append[F2[x]>:F[x],O2>:O](p2: => Proc2[F2,O2]): Proc2[F2,O2] =
    onHalt {
      case (End|Kill) => p2
      case e => fail(e)
    }
  final def ++[F2[x]>:F[x],O2>:O](p2: => Proc2[F2,O2]): Proc2[F2,O2] = append(p2)

  final def onComplete[F2[x]>:F[x],O2>:O](p2: => Proc2[F2,O2]): Proc2[F2,O2] =
    onHalt {
      case (End|Kill) => p2
      case e => p2.onHalt {
        case (End|Kill) => fail(e)
        case e2 => fail(Process.CausedBy(e2,e))
      }
    }

  final def onFailure[F2[x]>:F[x],O2>:O](p2: => Proc2[F2,O2]): Proc2[F2,O2] =
    onHalt {
      case e@(End|Kill) => fail(e)
      case e => p2.onHalt {
        case (End|Kill) => fail(e)
        case e2 => fail(Process.CausedBy(e2,e))
      }
    }

  final def flatMap[F2[x]>:F[x],O2](f: O => Proc2[F2,O2]): Proc2[F2,O2] =
    this match {
      case h@Halt(_) => h
      case a@Await(_,_) => a.extend(_.flatMap(f))
      case Suspend(p) => Suspend(p.map(_.flatMap(f)))
      case Emit(ht) => Emit(ht.flatMap { case (h,t) =>
        asEmit { (h map f).foldLeft(t.flatMap(f))((acc,h) => h append acc) }
      })
    }

  final def map[O2](f: O => O2): Proc2[F,O2] =
    flatMap { o => emit(f(o)) }

  final def pipe[O2](p2: Process1[O,O2]): Proc2[F,O2] = {
    import scalaz.stream.Process.{Await1, Emit => EmitP, Halt => HaltP}
    p2 match {
      case HaltP(e) => this.kill onComplete fail(e)
      case EmitP(h,t) => emitAll(h) ++ this.pipe(t)
      case Await1(recv,fb,c) => this match {
        case Halt(End) => halt.pipe(fb.disconnect)
        case Halt(e) => fail(e).pipe(c)
        case Emit(ht) => Suspend { ht.map { case (h, t) =>
          if (h.nonEmpty) (emitAll(h.tail) ++ t) pipe (recv(h.head))
          else t.pipe(p2)
        }}
        case a@Await(_,_) => a.extend(_.pipe(p2))
        case Suspend(p) => Suspend { p.map(_ pipe p2) }
      }
    }
  }

  final def tee[F2[x]>:F[x],O2,O3](p2: Proc2[F2,O2])(t: Tee[O,O2,O3]): Proc2[F2,O3] = {
    import scalaz.stream.tee.{AwaitL,AwaitR}
    import scalaz.stream.Process.{Emit => EmitP, Halt => HaltP}
    t match {
      case HaltP(e) => this.kill onComplete p2.kill onComplete fail(e)
      case EmitP(h,tl) => emitAll(h) ++ this.tee(p2)(tl)
      case AwaitL(recv,fb,c) => this match {
        case Halt(End) => halt.tee(p2)(fb.disconnect)
        case Halt(e) => fail(e).tee(p2)(c)
        case Emit(ht) => Suspend { ht.map { case (h, tl) =>
          if (h.nonEmpty) (emitAll(h.tail) ++ tl).tee(p2)(recv(h.head))
          else tl.tee(p2)(t)
        }}
        case a@Await(_,_) => a.extend(_.tee(p2)(t))
        case Suspend(p) => Suspend { p.map(_.tee(p2)(t)) }
      }
      case AwaitR(recv,fb,c) => p2 match {
        case Halt(End) => this.tee(halt)(fb)
        case Halt(e) => this.tee(fail(e))(c)
        // casts required since Scala seems to discard type of `p2` when
        // pattern matching on it - assigns `F2` and `O2` to `Any` in patterns
        case e@Emit(_) => e.asInstanceOf[Emit[F2,O2]].extend {
          (h: Seq[O2], tl: Proc2[F2,O2]) =>
            if (h.nonEmpty) this.tee(emitAll(h.tail) ++ tl)(recv(h.head))
            else this.tee(tl)(t)
        }
        case a@Await(_,_) => a.asInstanceOf[Await[F2,Any,O2]].extend(
          p2 => this.tee(p2)(t))
        case Suspend(p) => Suspend {
          (p.asInstanceOf[Trampoline[Proc2[F2,O2]]]).map(p2 => this.tee(p2)(t))
        }
      }
    }
  }
}

object Proc2 {

  // We are just using `Task` for its exception-catching and trampolining,
  // just defining local alias to avoid confusion
  type Trampoline[+A] = Task[A]
  val Trampoline = Task

  case class Halt(e: Throwable) extends Proc2[Nothing,Nothing]

  case class Await[+F[_],A,+O](
    req: F[A],
    recv: (Throwable \/ A) => Trampoline[Proc2[F,O]])
    extends Proc2[F,O] {

    def extend[F2[x]>:F[x],O2](f: Proc2[F,O] => Proc2[F2,O2]): Proc2[F2,O2] =
      Await[F2,A,O2](req, e => Trampoline.suspend(recv(e)).map(f))
  }

  case class Emit[F[_],O](
    uncons: Trampoline[(Seq[O], Proc2[F,O])])
    extends Proc2[F,O] {

    def extend[F2[x]>:F[x],O2](f: (Seq[O], Proc2[F,O]) => Proc2[F2,O2]):
        Proc2[F2,O2] =
      Suspend { uncons.map { case (h, t) => f(h, t) } }
  }

  case class Suspend[F[_],O](get: Trampoline[Proc2[F,O]]) extends Proc2[F,O]

  def suspend[F[_],O](p: => Proc2[F,O]): Proc2[F,O] =
    Suspend(Trampoline.delay(p))

  def lazily[F[_],O](p: => Proc2[F,O]): Proc2[F,O] = {
    lazy val pe = p
    Suspend(Trampoline.delay(pe))
  }

  private[stream] def Try[F[_],A](p: => Proc2[F,A]): Proc2[F,A] =
    try p
    catch { case e: Throwable => Halt(e) }

  val halt = Halt(End)

  def fail(err: Throwable): Proc2[Nothing,Nothing] =
    Halt(err)

  def emit[O](o: O): Proc2[Nothing,O] =
    Emit[Nothing,O](Trampoline.now(Vector(o) -> halt))

  def emitAll[O](s: Seq[O]): Proc2[Nothing,O] =
    Emit[Nothing,O](Trampoline.now(s -> halt))

  def asEmit[F[_],O](p: => Proc2[F,O]): Trampoline[(Seq[O], Proc2[F,O])] =
    Trampoline.delay((Vector.empty, p))

  def await[F[_],A,O](req: F[A])(recv: Throwable \/ A => Trampoline[Proc2[F,O]]):
      Proc2[F,O] =
    Await[F,A,O](req, recv)

  def eval[F[_],O](req: F[O]): Proc2[F,O] =
    Await[F,O,O](req, _.fold(
      e => Trampoline.now(fail(e)),
      a => Trampoline.now(emit(a))
    ))

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

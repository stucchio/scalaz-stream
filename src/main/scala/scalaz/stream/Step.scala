package scalaz.stream

import scalaz.Free.Trampoline
import scalaz.stream.Process._
import scalaz.{Trampoline, \/-, -\/, \/}

/**
 * Represents an intermediate step of a `Process`, including any current
 * emitted values, a next state, and the current finalizer / cleanup `Process`.
 */
case class Step[+F[_],+A](
  head: Throwable \/ Seq[A],
  tail: Process[F,A],
  cleanup: Process[F,A]) {
  def headOption: Option[Seq[A]] = head.toOption

  def fold[R](success: Seq[A] => R)(fallback: => R, error: => R): R =
    head.fold(e => if (e == Process.End) fallback else error, success)

  def isHalted = tail.isHalt
  def isCleaned = isHalted && cleanup.isHalt
}

object Step {
  def failed(e:Throwable) = Step(-\/(e),Halt(e),halt)
  val done = Step(-\/(End),halt,halt)
  def fromProcess[F[_],A](p:Process[F,A]):Step[F,A] = Step(\/-(Nil),p,halt)
}


/**
 * Represents an intermediate step of a `Process`.
 *
 * Step may be in `Cont` or `Done` state
 *
 */
sealed trait Step_[+F[_], +A]


object Step_ {

  case class Interruption(interrupt: () => Unit)

  object Interruption {
    /** Interruption that does nothing **/
    val noop: Interruption = Interruption(() => ())
  }

  /** Step, that continues by tail, and eventually emitted Head **/
  case class Cont[F[_], A](
    head: Seq[A]
    , tail: Process[F, A]
    , recv: Throwable \/ A => Trampoline[Process[F, A]]
    ) extends Step_[F, A]

  /** Process has finished, contains reason that caused process to finish **/
  case class Done(rsn: Throwable) extends Step_[Nothing, Nothing]

  /** Builds the initial step of process that has not been run yet **/
  def init[F[_], A](p: Process[F, A]): Step_[F, A] = Cont[F, A](Nil, p, _ => Trampoline.done(halt))

  /** Builds intermediate step of the process **/
  def cont[F[_], A](h: Seq[A], p: Process[F, A]): Step_[F, A] =
    Step_.Cont[F, A](h, p, {
      case \/-(_)   => Trampoline.done(halt)
      case -\/(rsn) => Trampoline.delay(p.cleanup(rsn))
    })

}


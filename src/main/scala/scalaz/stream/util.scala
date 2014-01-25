package scalaz.stream

import scala.annotation.tailrec

/**
 * Created by pach on 25/01/14.
 */
private[stream] object util {

  //hack for Vector bug @see SI-7725.
  def fast_++[X](s1:Seq[X],s2:Seq[X]):Seq[X] = {
    @tailrec
    def go(v1:Vector[X], t:Seq[X]):Seq[X] = {
      if (t.isEmpty) v1
      else go(v1 :+ t.head, t.tail)
    }
    go(s1.toVector,s2)
  }

}

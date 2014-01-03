package scalaz.stream

import scala.util.Success
import scala.util.Try

/**
 * Created by pach on 03/01/14.
 */
object debug {

  def apply(s:String,other:Any*) = {
    //println(s,other.map(v=>Try(v.toString()).transform(s=>Success(s),t=>Success(t.toString)).get).mkString(","))
  }

}

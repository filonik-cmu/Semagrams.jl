package semagrams.sprites

import com.raquo.laminar.api.L.svg._
import com.raquo.laminar.api._
import semagrams.util._
import semagrams._
import semagrams.acsets._
import semagrams.layout._

extension [A](s: L.Signal[Seq[A]]) {
  def splitWithIndexAndLength[B, C](
      f: A => B
  )(g: (B, A, L.Signal[A], L.Signal[(Int, Int)]) => C): L.Signal[Seq[C]] = {
    s.map(xs => {
      val n = xs.length
      xs.zipWithIndex.map({ case (a, i) => (a, (i, n)) })
    }).split(t => f(t._1))((b, t, $t) => g(b, t._1, $t.map(_._1), $t.map(_._2)))
  }
}

/** A Sprite that is a box with ports on both sides.
  *
  * @param `boxSprite`
  *   the sprite to use for the box
  *
  * @param `inPortSprite`
  *   the sprite to use for the left hand ports
  *
  * @param `outPortSprite`
  *   the sprite to use for the right hand ports
  *
  * @param inPort
  *   the object in the subschema to query for the list of in ports
  *
  * @param outPort
  *   the object in the subschema to query for the list of out ports
  */
case class DPBox(
    boxSprite: Sprite,
    inPortSprite: Sprite,
    outPortSprite: Sprite,
    inPort: Ob,
    outPort: Ob
) extends Sprite {

  def computePortCenters(data: ACSet): ACSet = {
    def helper(acs: ACSet, portOb: Ob, dir: (-1) | 1): ACSet = {
      import Complex.im
      val ports = acs.parts(ROOT, portOb)
      val bbox = boxSprite.bbox(ROOT, acs).get
      val sideCenter = bbox.pos + bbox.dims / 2 + (dir * bbox.dims.x / 2)
      val spacer = FixedRangeExceptEnds(-bbox.dims.y / 2, bbox.dims.y / 2)
      val n = ports.length
      val cs = ports.zipWithIndex.map(
        { case ((p, sub), i) =>
          (p, sideCenter + spacer.assignPos(i, n) * im)
        }
      )
      cs.foldLeft(acs)((acs, pc) => acs.setSubpart(pc._1, Center, pc._2))
    }
    helper(helper(data, inPort, -1), outPort, 1)
  }

  def present(
      ent: Entity,
      init: ACSet,
      updates: L.Signal[ACSet],
      attachHandlers: HandlerAttacher
  ): L.SvgElement = {
    val rect = boxSprite.present(ent, init, updates, attachHandlers)

    val layout = updates.map(computePortCenters)

    val inPorts = layout
      .map(_.parts(ROOT, inPort))
      .split(_._1)((p, d, $d) => {
        inPortSprite.present(ent.extend(p), d._2, $d.map(_._2), attachHandlers)
      })

    val outPorts = layout
      .map(_.parts(ROOT, outPort))
      .split(_._1)((p, d, $d) => {
        outPortSprite.present(ent.extend(p), d._2, $d.map(_._2), attachHandlers)
      })

    g(
      rect,
      L.children <-- inPorts,
      L.children <-- outPorts
    )
  }

  override def boundaryPt(
      subent: Entity,
      data: ACSet,
      dir: Complex
  ): Option[Complex] = subent match {
    case Part(Nil) => boxSprite.boundaryPt(subent, data, dir)
    case _         => None
  }

  override def center(subent: Entity, data: ACSet): Option[Complex] =
    subent match {
      case Part(Nil) => data.props.get(Center)
      case Part((ob, i) :: Nil) if ob == inPort || ob == outPort => {
        computePortCenters(data).trySubpart(Center, subent.asInstanceOf[Part])
      }
      case _ => None
    }

  override def bbox(subent: Entity, data: ACSet) = subent match
    case p: Part => 
      val (spr,tail) = p.ty.path match
        case Seq() => (boxSprite,ROOT)
        case ip if ip == Seq(inPort) => (inPortSprite,p.tail)
        case op if op == Seq(outPort) => (outPortSprite,p.tail)
        case _ => throw msgError(s"DPBox bbox: unmatched part $subent")      
      spr.bbox(tail,computePortCenters(data).subacset(p))
    case _ => throw msgError(s"DPBox bbox: unmatched entity $subent")
    
  
}

package semagrams

import semagrams._
import semagrams.acsets.{given, _}
import semagrams.ui._
import semagrams.util._
import semagrams.widgets._
import monocle._

import upickle.default._

import com.raquo.laminar.api.L._
import cats.effect._
import semagrams.controllers.HoverController
import semagrams.acsets.WiringDiagrams.OutPort
import semagrams.acsets.WiringDiagrams.InPort
import semagrams.sprites.Middleware
import semagrams.sprites.WithMiddleware
import semagrams.sprites.DPBox
import semagrams.sprites.Rect
import cats.data.State

/** This class bundles the common arguments to many actions one might want to do
  * in a binding so that you don't have to pass them in every time, and then
  * provides a bunch of commonly used actions as methods.
  */
case class Actions(
    es: EditorState,
    m: UndoableVar[ACSet],
    ui: UIState
) {
  import ACSet._

  /** Add and return a part to the model with type `Ob` and subacset given by
    * `init`, and set the [[Center]] of that new part to be the current mouse
    * position
    */
  def addAtMouse(ob: Ob, init: ACSet): IO[Part] = es.mousePos.flatMap(
    pos => add(es.bgPart,ob,init.setSubpart(ROOT,Center,pos))
  )
      
  /** Add and return a part to the model with type `Ob` and [[Center]] the
    * current mouse position.
    */
  def addAtMouse(ob: Ob): IO[Part] = addAtMouse(ob,ob.schema())

    
  /** [[addAtMouse]] without returning the part */
  def addAtMouse_(ob: Ob, init: ACSet): IO[Unit] =
    addAtMouse(ob, init).map(_ => ())

  /** [[addAtMouse]] without returning the part */
  def addAtMouse_(ob: Ob): IO[Unit] = addAtMouse(ob).map(_ => ())

  /** Add and return a part to the acset at `p` of type `ob` with properties
    * `props`
    */
  def add(p: Part, ob: Ob, props: PropMap): IO[Part] = m.updateS(
    addPart(p, ob, props)
  )

  /** Add and return a part to the acset at `p` of type `ob` with properties
    * `props`
    */
  def add(p: Part, ob: Ob, init: ACSet): IO[Part] = m.updateS(
    addPart(p, ob, init)
  )


  /** [[add]] without returning the part */
  def add_(p: Part, ob: Ob, props: PropMap | ACSet): IO[Unit] = props match
    case pm: PropMap => add(p, ob, pm).map(_ => ())
    case a: ACSet => add(p, ob, a).map(_ => ())

  /** Set the value of the property `f` at the part `p` to be `v` */
  def set(p: Part, f: Property, v: f.Value,check:Boolean=true): IO[Unit] = (f,v) match
    case (h,pt):(Hom,Part) =>
      if check && !h.doms.exists(dom => (p - es.bgPart).in(dom) )
      then 
        throw msgError(s"$p not in dom($h)")
      else if check && !h.codoms.exists(codom => (pt - es.bgPart).in(codom))
      then 
        throw msgError(s"$v not in codom($h)")
      else 
        m.updateS(setSubpart(p, h, pt))
    case (a,_):(Attr,Any) =>
      if check && a.doms.exists(dom => (p - es.bgPart).in(dom) )
      then m.updateS(setSubpart(p, f, v))
      else throw msgError(s"$p not in dom($a)")
    case _ => 
      m.updateS(setSubpart(p, f, v))
    

  /** Unset the value of the property `f` of the part `p` */
  def remove(p: Part, f: Property): IO[Unit] = m.updateS(
    remSubpart(p, f)
  )

  /** Remove the part `p` */
  def remove(p: Part): IO[Unit] = for
    _ <- m.updateS(remPart(p))
    _ = es.hover.$state.set(HoverController.State(None))
  yield ()

  /** Remove the part currently hovered */
  val del = fromMaybe(es.hoveredPart).flatMap(p => 
    if p > es.bgPart && p != es.bgPart
    then remove(p)
    else die 
  )

  /** Try to get the bounding box of `b` as it is currently displayed on the screen */
  def tryBBox(b: Part): Option[BoundingBox] = b match
    case _ if es.bgPart == b =>
      val sz = es.size.now()
      Some(BoundingBox(sz / 2.0, sz))
    case _ if b > es.bgPart =>
      val ext = b - es.bgPart
      es.entities.now().em.get(ext.head)
        .flatMap {
          case (spr,acset) => spr.bbox(ext.tail, acset)
        }
    case _ => None

  def getBBox(b: Part): BoundingBox = tryBBox(b).getOrElse(
    throw msgError(s"No bounding box for part $b")
  )

  def getPos(b:Part) = getBBox(b).pos
  def getDims(b:Part) = getBBox(b).dims

        
  /** A generalized drag action
    *
    * At the beginning, compute a value `memo` of type `Memo` from the mouse
    * position.
    *
    * While the drag is happening, execute `during` with `memo` and the mouse
    * position of the drag.
    *
    * Finally, call `after` with `memo` to get the return value.
    *
    * If the drag is canceled, this resets the state.
    *
    * @todo
    *   - It seems like we do not use the parameter `s` anywhere; we may want to
    *     remove it
    *   - `during` executes for side-effects, which could be anything! But we
    *     are assuming that all it does is change `m`. We should express this
    *     explicitly by having `during` return a function `ACSet => ACset`, and
    *     then hook that into modifying `m`.
    *   - Is `drag` the best name for this?
    */
  def drag[Memo, Return](
      start: (z: Complex) => IO[Memo],
      during: (Memo, Complex) => Unit,
      after: Memo => IO[Return]
  )(s: Part): IO[Return] = for {
    _ <- IO(m.save())
    _ <- IO(m.unrecord())
    m0 = m.now()
    memo <- es.mousePos.flatMap(start)
    ret <- (es.drag.drag(Observer(p => during(memo, p))) >> after(memo))
      .onCancelOrError(IO({ 
        m.set(m0) 
        es.drag.$state.set(None)
      }).flatMap(_ => die))
    _ <- IO(m.record())
  } yield ret 




  // Memo = Offset, Return = Unit
  def dragMove(i: Part) =

    val pt = es.bgPlus(i)

    def start = (z:Complex) => for 
      _ <- m.updateS_(moveFront(pt))
      c <- IO(m.now().subpart(Center, pt))
    yield c - z

    def during = (offset:Complex,p:Complex) => m.update(
      _.setSubpart(pt, Center, p + offset)
    )

    def after = (offset: Complex) => IO(())

    drag(start,during,after)(pt)



  val debug = 
    val sch = m.now().schema
    import sch._
    IO(m.now()).map(acset => println(write(acset)))
    
  def die[A]: IO[A] = fromMaybe(IO(None))
  


  def liftTo(p:Part,ext:Seq[(Ob,Int)]): IO[Part] = ext match
    case Seq() => 
      IO(p)
    case (ob,i) +: rest => for {
      a <- add(p,ob,PropMap())
      _ <- IO(m.update((acset:ACSet) =>
        acset.moveToIndex(a,i)
      ))
      last <- liftTo(a,ext.tail)
    } yield last
         
    
  /** Drag to construct an edge between two parts
    *
    * Returns the part corresponding to the edge that was constructed
    */

  // TODO: Split src/target logic
  // TODO: Add dragSpan, dragCospan

  def dragEdge(
    ob:Ob,
    src:Hom,
    tgt:Hom,
    lift: (Part,Complex) => Seq[(Ob,Int)] = (_,_) => Seq()
  )(s: Part): IO[Part] =
      
    def start = (z:Complex) =>
      
      for
        p <- {lift(s,z) match
          case Seq() => IO(s)
          case ext => liftTo(s,ext)
        }

        w <- add(
          es.bgPart,
          ob,
          (p - es.bgPart).ty match
            case tp if src.codoms.contains(tp) =>
              PropMap()
                .set(src, p)
                .set(End, z)
                .set(Interactable, false)
            case tp if tgt.codoms.contains(tp) =>
              PropMap()
                .set(tgt, p)
                .set(Start, z)
                .set(Interactable, false)
        )
      yield w

    def during = (e: Part, p: Complex) =>
      if m.now().hasSubpart(End, e)
      then m.update(_.setSubpart(e, End, p))
      else m.update(_.setSubpart(e, Start, p))

    def after = (e:Part) => for
      t <- fromMaybe(es.hoveredPart)
      z <- es.mousePos
      q <- lift(t,z) match
        case Seq() => IO(t)
        case ext => liftTo(t,ext)
      _ <- m.now() match
        case mnow if mnow.hasSubpart(src,e) => for {
          _ <- set(e, tgt, q)
          _ <- remove(e, End)
          _ <- remove(e, Interactable)
        } yield ()
        case mnow if mnow.hasSubpart(tgt,e) => for {
          _ <- set(e,src,q)
          _ <- remove(e,Start)
          _ <- remove(e,Interactable)
        } yield ()
        case _ => die
    yield e

    drag(start, during, after)(s)



  
  def unplug(
    p:Part,
    w:Part,
    src:Hom,
    tgt:Hom,
    lift: (Part,Complex) => Seq[(Ob,Int)] = (_,_) => Seq()
  ) =
    def start(z:Complex) = if m.now().trySubpart(src,w) == Some(p)
      then (for
        _ <- set(w,Start,z,false)
        _ <- set(w,Interactable,false,false)
        _ <- remove(w,src)
        _ <- if (m.now().incident(p,src) ++ m.now().incident(p,tgt)).isEmpty
          then remove(p)
          else IO(())
      yield w)
      else (for
        _ <- set(w,End,z,false)
        _ <- set(w,Interactable,false,false)
        _ <- remove(w,tgt)
        _ <- if (m.now().incident(p,src) ++ m.now().incident(p,tgt)).isEmpty
          then remove(p)
          else IO(())
      yield w)

    def during = (e: Part, p: Complex) =>
      if m.now().hasSubpart(End, e)
      then m.update(_.setSubpart(e, End, p))
      else m.update(_.setSubpart(e, Start, p))


    def after = (e:Part) => for
      t <- fromMaybe(es.hoveredPart)
      z <- es.mousePos
      q <- lift(t,z) match
        case Seq() => IO(t)
        case ext => liftTo(t,ext)
      _ <- m.now() match
        case mnow if mnow.hasSubpart(src,e) => for {
          _ <- set(e, tgt, q)
          _ <- remove(e, End)
          _ <- remove(e, Interactable)
        } yield ()
        case mnow if mnow.hasSubpart(tgt,e) => for {
          _ <- set(e,src,q)
          _ <- remove(e,Start)
          _ <- remove(e,Interactable)
        } yield ()
        case _ => die
    yield e

    drag(start, during, after)(p)


  /** Edit the content of the part `i`, using popup text box */
  def edit(p: Property { type Value = String; }, multiline: Boolean)(
      i: Part
  ): IO[Unit] = for {
    _ <- IO(
      m.update(acs =>
        if (acs.trySubpart(p, i).isEmpty) {
          acs.setSubpart(i, p, "")
        } else {
          acs
        }
      )
    )
    _ <- ui.addKillableHtmlEntity(kill => {
      val v = m.zoomL(subpartLens(p, i))
      val t = TextInput(v, multiline)(kill)
      if (multiline) {
        PositionWrapper(Position.topToBotMid(10), t)
      } else {
        PositionWrapper(Position.botMid(10), t)
      }
    })
  } yield ()

  /** Bring up a textbox that can be used for copy/pasting the serialized
    * version of the current state
    */
  def importExport = ui.addKillableHtmlEntity(kill =>
    val sch = m.now().schema
    import sch._
    PositionWrapper(
      Position.topToBotMid(10),
      TextInput(
        m.zoomL(Lens(
          (acset:ACSet) => write(acset,2)
        )(
          s => a => try
            read[ACSet](s)
          catch case e => 
            println(s"importExport error $e")
            a
        )),
        true
      )(kill)
    )
  )


  def zoomIn(
    b:Part,
    layout:(sz:Complex,acset:ACSet) => ACSet,
    esources:Seq[EntitySource[ACSet]]
  ) =  
    es.hover.$state.set(HoverController.State(None))
    es.currentView.set(b)
    es.deregister("mainVP")
    es.makeViewport(
      "mainVP",
      es.size.signal
        .combineWith(m.signal.map(_.subacset(b)))
        .map(layout.tupled),
      esources
    )


  def zoomOut(
    layout:(sz:Complex,acset:ACSet) => ACSet,
    esources:Seq[EntitySource[ACSet]]
  ) = 
    val b = es.bgPart match
      case ROOT => ROOT
      case p => p.init
    zoomIn(ROOT,layout,esources).flatMap(_ => zoomIn(b,layout,esources))


}

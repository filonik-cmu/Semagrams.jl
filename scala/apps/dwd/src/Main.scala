package semagrams.dwd

import semagrams.api._
import semagrams.acsets.{_, given}

import upickle.default._
import com.raquo.laminar.api.L._
import cats.effect._
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

import org.scalajs.dom

case object SchDWD extends Schema {

  val obs = Seq(DWDObs.values*)
  val homs = Seq(DWDHoms.values*)
  val attrs = Seq(DWDAttrs.values*)

  enum DWDObs(_schema: Schema = SchEmpty) extends Ob:
    override lazy val schema = _schema
    case InPort, OutPort, Wire
    case Box extends DWDObs(SchDWD)

  import DWDObs._

  enum DWDHoms(val doms: Seq[PartType], val codoms: Seq[PartType]) extends Hom:
    case Src
        extends DWDHoms(
          Wire.asDom(),
          InPort.asDom() :+ Box.extend(OutPort)
        )
    case Tgt
        extends DWDHoms(
          Wire.asDom(),
          OutPort.asDom() :+ Box.extend(InPort)
        )

  enum DWDAttrs(val doms: Seq[PartType]) extends Attr:
    case PortType
        extends DWDAttrs(
          Wire.asDom() ++ InPort.asDom() ++ OutPort.asDom()
            :+ Box.extend(InPort) :+ Box.extend(OutPort)
        )
        with PValue[DWDType]

  enum DWDType derives ReadWriter:
    case Person, Document, Standard, Requirement

    def props = this match
      case Person      => PropMap() + (Fill, "red") + (Stroke, "red")
      case Document    => PropMap() + (Fill, "blue") + (Stroke, "blue")
      case Standard    => PropMap() + (Fill, "green") + (Stroke, "green")
      case Requirement => PropMap() + (Fill, "purple") + (Stroke, "purple")

}

import SchDWD._
import DWDObs._
import DWDHoms._
import DWDAttrs._

extension (a: Actions) {

  /** Select a new position (in/out + index) based on the mouse position */
  def liftPort(p: Part, pos: Complex): Seq[(Ob, Int)] =
    val (es, m) = (a.es, a.m)
    val ext = p - es.bgPart
    val (sz, c) = ext.ty.path match
      case Seq() =>
        (
          es.size.now(),
          es.size.now() / 2.0
        )
      case Seq(Box) =>
        (
          a.getDims(p),
          m.now()
            .trySubpart(Center, p)
            .getOrElse(
              throw msgError(s"missing center for $p")
            )
        )
      case _ =>
        return Seq()

    val ptype = if pos.x < c.x then InPort else OutPort

    val nports = m.now().parts(p, ptype).length
    val j = DPBox.portNumber(pos - c + (sz / 2.0), sz, nports)
    Seq((ptype, j))

  /** Set the type of `p` and any other ports or wires connected to it */
  def setType(p: Part, tpe: DWDType): IO[Unit] =
    val (es, m) = (a.es, a.m)
    p.lastOb match
      case Wire => (
        for
          _ <- a.set(p, PortType, tpe)
          s = m.now().trySubpart(Src, p)
          t = m.now().trySubpart(Tgt, p)
          _ <- s match
            case Some(p) if m.now().trySubpart(PortType, p) != Some(tpe) =>
              setType(p, tpe)
            case _ => IO(())
          _ <- t match
            case Some(p) if m.now().trySubpart(PortType, p) != Some(tpe) =>
              setType(p, tpe)
            case _ => IO(())
        yield ()
      )
      case InPort | OutPort => (
        for
          _ <- a.set(p, PortType, tpe)
          ws = (m.now().incident(p, Src) ++ m.now().incident(p, Tgt))
            .filter(w => m.now().trySubpart(PortType, w) != Some(tpe))
          _ <- a.doAll(ws.map(setType(_, tpe)))
        yield ()
      )

  /** Set the types of `p` and `q` to be equals, unless both are defined and
    * different, in which case die.
    */
  def eqTypes(p: Part, q: Part): IO[Unit] =
    val ptpe = a.m.now().trySubpart(PortType, p)
    val qtpe = a.m.now().trySubpart(PortType, q)
    (ptpe, qtpe) match
      case (Some(tpe1), Some(tpe2)) =>
        if tpe1 == tpe2
        then IO(())
        else a.die
      case (Some(tpe), None) => setType(q, tpe)
      case (None, Some(tpe)) => setType(p, tpe)
      case (None, None)      => IO(())

  def test(p: Part) = a.es.mousePos.map(z => println(s"test: mousePos = $z"))

  def boxMenu = Seq(("Rename", (b: Part) => a.edit(Content, true)(b)))

  def menuItem(tpe: DWDType) = (
    s"Set type to $tpe",
    (p: Part) => a.setType(a.es.bgPlus(p), tpe)
  )

  def wireMenu: Seq[(String, Part => IO[Unit])] =
    DWDType.values.toSeq.map(menuItem)

}

val layout = DPBox.layoutPortsBg(InPort, OutPort)

import MouseButton._
import KeyModifier._

val helpText = """
  ∙ Double click  = add box/edit labels
  ∙ Drag = Drag box
  ∙ Shift + Drag = Add/unplug wire
  ∙ Ctrl + Double click = Zoom into box/out of background
  ∙ "d" = Delete element below mouse
  ∙ "s" = Open import/export window
  ∙ "w" = Open Tikz export window
  ∙ Ctrl + "z"/Shift + Ctrl + "Z" = undo/redo
  ∙ Right click on wires/ports to set types
"""

def bindings(
    es: EditorState,
    g: UndoableVar[ACSet],
    ui: UIState,
    vp: Viewport
) = {

  val a = Actions(es, g, ui)

  Seq(
    // test hovered part
    keyDown("t").andThen(fromMaybe(es.hoveredPart).flatMap(a.test)),

    // Add box at mouse
    dblClickOnPart(Left, ROOT.ty)
      .withMods()
      .flatMap(_ =>
        for
          b <- a.addAtMouse(Box)
          _ <- a.edit(Content, true)(b)
        yield b
      ),

    // Edit box label
    dblClickOnPart(Left)
      .withMods()
      .map(b => es.bgPlus(b))
      .flatMap(a.edit(Content, true)),

    // Zoom into box
    dblClickOnPart(Left, PartType(Seq(Box)))
      // .withMods(Ctrl)
      .withAltMods(Set(Ctrl), Set(Meta))
      .map(b => es.bgPlus(b))
      .flatMap(b => a.zoomIn(b, layout, entitySources)),

    // Zoom out of box
    dblClickOn(Left)
      // .withMods(Ctrl)
      .withAltMods(Set(Ctrl), Set(Meta))
      .flatMap(_ => a.zoomOut(layout, entitySources)),

    // Drag box
    clickOnPart(Left)
      .withMods()
      .flatMap(p =>
        p.ty match
          case ROOT.ty                => es.mousePos.flatMap(z => IO(()))
          case PartType(Seq(Box, _*)) => a.dragMove(p.head)
      ),

    // Drag wire (or unplug)
    clickOnPart(Left)
      .withMods(Shift)
      .map(es.bgPlus(_))
      .flatMap(p =>
        val wires = (g.now().incident(p, Src) ++ g.now().incident(p, Tgt))
          .filter(_.ty == es.bgPart.ty.extend(Wire))

        if wires.isEmpty
        then
          (for
            w <- a.dragEdge(Wire, Src, Tgt, a.liftPort, a.eqTypes)(p)
            _ <- a.edit(Content, true)(w)
          yield w)
        else a.unplug(p, wires(0), Src, Tgt, a.liftPort, a.eqTypes)
      ),

    // Menu actions
    clickOnPart(Right).flatMap(p =>
      p.lastOb match
        case Wire | InPort | OutPort =>
          es.makeMenu(ui, a.wireMenu)(p)
        case Box =>
          es.makeMenu(ui, a.boxMenu)(p)
        case _ => a.die
    ),

    // Delete part
    keyDown("d").andThen(a.del),

    // Undo
    keyDown("z")
      .withAltMods(Set(Ctrl), Set(Meta))
      .andThen(IO(g.undo())),

    // Redo
    keyDown("Z")
      .withAltMods(Set(Ctrl, Shift), Set(Meta, Shift))
      .andThen(IO(g.redo())),

    // Print current state
    keyDown("?").andThen(a.debug),

    // Print help text
    keyDown("h").andThen(IO(dom.window.alert(helpText))),

    // Open serialization window
    keyDown("s").andThen(a.importExport),

    // Open tikz export window
    keyDown("w").andThen(
      a.exportTikz(
        Seq(Box, InPort, OutPort, Wire),
        Seq(InPort, OutPort)
      )
    )
  )
}

def portDir(p: Part) = p.ty.path match {
  case Seq(Box, OutPort) | Seq(InPort) => 10.0
  case Seq(Box, InPort) | Seq(OutPort) => -10.0
  case _                               => 0.0
}

def style(acs: ACSet, p: Part): PropMap = (p.lastOb match
  case Wire             => acs.props.get(PortType)
  case InPort | OutPort => acs.props.get(PortType)
  case _                => None
).map(_.props).getOrElse(PropMap())

val entitySources = (es: EditorState) =>
  Seq(
    ACSetEntitySource(Box, AltDPBox(InPort, OutPort, style)(es))
      .withProps(PropMap() + (FontSize, 22)),
    ACSetEntitySource(InPort, BasicPort(PropMap())(es))
      .withProps(PropMap() + (MinimumWidth, 40))
      .addPropsBy((e: Entity, acs: ACSet, em: EntityMap) =>
        acs.props ++ style(acs, e.asInstanceOf[Part])
      ),
    ACSetEntitySource(OutPort, BasicPort(PropMap())(es))
      .withProps(PropMap() + (MinimumWidth, 40))
      .addPropsBy((e: Entity, acs: ACSet, em: EntityMap) =>
        acs.props ++ style(acs, e.asInstanceOf[Part])
      ),
    ACSetEntitySource(Wire, BasicWire(Src, Tgt)(es))
      .addPropsBy((ent, acs, emap) =>
        wireProps(Src, Tgt, style, portDir, es.bgPart)(ent, acs, emap)
      )
  )

object Main {
  @JSExportTopLevel("App")
  object App extends Semagram {

    def run(es: EditorState, init: Option[String]): IO[Unit] = {
      implicit val rw: ReadWriter[(ACSet, Complex)] = SchDWD.runtimeSerializer("dims", es.size.now())
      
      val (acs, oldDims) = initOpt
        .flatMap(initstr =>
          read[Map[String, (ACSet, Complex)]](initstr)
            .get("pie_str")
        )
        .getOrElse(
          (ACSet(SchDWD), Complex(1, 1))
        )
      
      def saveButton(g: UndoableVar[ACSet], filename: String): Element =
        a(
          idAttr := "save-anchor",
          button(
            //cls := "btn",
            "Save",
            onClick --> {
              (evt: dom.MouseEvent) => {
                val a = dom.document.getElementById("save-anchor").asInstanceOf[dom.html.Anchor]
                val acsetJsonData = write[(ACSet, Complex)]((g.now(), es.size.now()))
                val acsetURIComponent = js.URIUtils.encodeURIComponent(acsetJsonData)
                a.setAttribute("href", s"data:text/json;charset=utf-8,$acsetURIComponent")
                a.setAttribute("download", filename)
                // Unlike other solutions, we do not need to simulate a click because event will bubble
                //a.click()
              }
            }
          ),
        )

      def loadButton(g: UndoableVar[ACSet]): Element =
        span(
          input(
            //cls := "hidden",
            idAttr := "load-input",
            `type` := "file",
            onChange --> {
              evt => {
                var target = evt.target.asInstanceOf[dom.html.Input]
                var file = target.files(0)
                var reader = new dom.FileReader();
                reader.onload = (evt) => {
                  val result = reader.result.asInstanceOf[String]
                  val (acs, oldDims) = read[(ACSet, Complex)](result)
                  acs.scale(oldDims, es.size.now())
                  g.update { _ => acs }
                  //dom.console.log(result)
                }
                reader.onerror = (evt) => {
                  dom.console.log("Error reading:", file)
                } 
                reader.readAsText(file)
              }
            }
          ),
          /*
          label(
            forId := "load-input",
            cls := "btn",
            "Load"
          ),
          */
        )

      val appContainer = dom.document.getElementById("app-container")

      val ctrls = div(idAttr := "controls", styleAttr := "display: flex; flex-direction: row; gap: 5px; margin-top: 10px")

      render(appContainer, ctrls)

      es.elt.amend(
        svg.text(
          "Press \"h\" for help text",
          svg.y := "99.5%",
          svg.style := "user-select: none",
        )
      )

      for {
        g <- IO(UndoableVar(acs.scale(oldDims, es.size.now())))
        lg <- IO(
          es.size.signal
            .combineWith(g.signal)
            .map(layout.tupled)
        )
        vp <- es.makeViewport(
          es.MainViewport,
          lg,
          entitySources(es)
        )
        ui <- es.makeUI()
        _ = {
          ctrls.amend(
            saveButton(g, "acset.json"),
            loadButton(g),
          )
        }
        _ <- es.bindForever(bindings(es, g, ui, vp))
      } yield ()
    }
  }
}

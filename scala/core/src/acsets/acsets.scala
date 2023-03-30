package semagrams.acsets

import semagrams._
import cats.data.State
import scala.collection.mutable
import upickle.default._
import monocle.Lens

import scala.language.implicitConversions

/** A trait marking objects in a [[Schema]] */
trait Ob {

  /** The subschema for the subacsets on parts of this type */
  val schema: Schema = SchEmpty

  /** This object promoted to type for the domain/codomain of a morphism */
  def asDom() = Seq(PartType(Seq(this)))
}

/** A trait marking morphisms in a [[Schema]]
  *
  * Unlike in typical categories, morphisms can have multiple domains and
  * multiple codomains. This is shorthand for a morphism for each pair of domain
  * and codomain.
  *
  * Additionally, domains/codomains for morphisms are sequences of objects,
  * representing paths through the schema, so a morphism goes between multiple
  * levels of the schema.
  */
trait Hom extends Property {

  /** The possible domains of this morphism */
  val doms: Seq[PartType]

  /** The possible codomains of this morphism */
  val codoms: Seq[PartType]

  type Value = Part

  /** Any instance of [[Property]] needs a serializer/deserializer.
    *
    * This is a temporary hack that works in a specific case.
    *
    * @todo
    *   figure out nested acset serialization
    */
  val rw = summon[ReadWriter[Int]].bimap(
    _.path(0)._2.id,
    i => Part(Seq((codoms(0).path(0), Id(i - 1))))
  )

}

/** A trait marking attributes in a [[Schema]]
  *
  * Unlike in Julia ACSets, we do not have AttrTypes. Rather, attributes have
  * their associated scala type given by Property.Value.
  */
trait Attr extends Property {

  /** Like [[Hom]], this can have multiple domains; we interpret this
    * mathematically as a separate attribute for each domain, but this makes it
    * easier to write down.
    */
  val dom: Seq[PartType]
}

/** The type of a part in a nested acset is a sequence of objects, going down
  * through the nested schemas.
  *
  * So `PartType(Seq())` is the type of the root acset part, and
  * `PartType(Seq(Box,IPort))` refers to the type of input ports on boxes.
  */
case class PartType(path: Seq[Ob]) extends EntityType {

  /** Add an object to the end of the PartType */
  def extend(x: Ob): PartType = PartType(path :+ x)

  /** The first object in the PartType */
  def head: Ob = path(0)

  /** The PartType relative to the first object */
  def tail: PartType = PartType(path.tail)

  /** Returns if `this` an extension of `that`? */
  def <(that: PartType): Boolean = that.path match
    case Seq() => true
    case Seq(thathead, thattail @ _*) =>
      head == thathead && tail < PartType(thattail)
}

object PartType {
  given obIsPartType: Conversion[Ob, PartType] = (ob: Ob) => PartType(Seq(ob))
  given seqIsPartType: Conversion[Seq[Ob], PartType] = PartType(_)
}

/** A part is a path through a nested acset. If you visualize a nested acset as
  * a tree, where each node is an acset and its children are all of its
  * subacsets, then a part tells at each level which subacset to choose.
  *
  * The empty path refers to the root acset.
  *
  * @todo
  *   We should have "relative" parts and "absolute" parts, just like in a
  *   filesystem there are relative and absolute paths. I think that currently
  *   `Part` is used for both notions, which is very confusing.
  */
case class Part(path: Seq[(Ob, Id)]) extends Entity {

  /** All of the objects of the path */
  override val ty: PartType = PartType(path.map(_._1))

  /** Provide directions to go one acset deeper */
  def extend(x: Ob, i: Id) = Part(path :+ (x, i))

  /** Honestly not sure if this is a good method? */
  override def extend(e: Entity) = e match {
    case (p: Part) => Part(path ++ p.path)
    case _         => SubEntity(this, e)
  }

  def head: Part = Part(path.slice(0, 1))
  def tail: Part = Part(path.tail)

  /** Checks if `this` is more specific than `that` */
  def <(that: Part): Boolean = that.path match
    case Seq() => true
    case Seq(thathead, thattail @ _*) =>
      head == thathead && tail < Part(thattail)

  /** An alias for `<` */
  def in(ptype: PartType) = ty < ptype
}

/** The schema for a nested acset.
  *
  * The nested part comes in because anything that implements `Ob` has another
  * schema attached to it.
  *
  * @todo
  *   What does this correspond to categorically?
  */
trait Schema {
  val obs: Seq[Ob]
  val homs: Seq[Hom]
  val attrs: Seq[Attr]

  /** Returns the subschema found by following the path in `ty`. */
  def subschema(ty: PartType): Schema = ty.path match {
    case Nil => this
    case ob :: rest => {
      assert(obs contains ob)
      ob.schema.subschema(PartType(rest))
    }
  }

  /** Returns all of the homs that go into the given part type Each hom is
    * prefixed by a path of objects needed to get to that hom
    */
  def homsInto(ty: PartType): Seq[(Seq[Ob], Hom)] = ty.path match {
    case Nil => Seq()
    case ob :: rest =>
      homs.filter(_.codoms contains ty).map((Seq(), _))
        ++ ob.schema
          .homsInto(PartType(rest))
          .map({ case (obs, f) => (ob +: obs, f) })
  }

}

/** An opaque wrapper around an integer */
case class Id(id: Int)

/** Storage class for the parts corresponding to an `Ob` in a schema.
  *
  * This is immutable; methods that logically mutate simply return new objects.
  *
  * @param nextId
  *   The id to assign to the next part added
  *
  * @param ids
  *   The ids of all the parts added so far. This is a `Seq` because we care
  *   about the ordering; when the ACSet is displayed this ordering is used when
  *   two sprites overlap.
  *
  * @param acsets
  *   The subacset corresponding to each id
  */
case class Parts(
    nextId: Int,
    ids: Seq[Id],
    acsets: Map[Id, ACSet]
) {

  /** Add multiple new parts, with subacsets given in `partacsets`.
    *
    * Returns a sequence of the ids of the added parts.
    */
  def addParts(partacsets: Seq[ACSet]): (Parts, Seq[Id]) = {
    val newIds = nextId.to(nextId + partacsets.length - 1).map(Id.apply)
    val newPd = Parts(
      nextId + partacsets.length,
      ids ++ newIds,
      acsets ++ newIds.zip(partacsets).toMap
    )
    (newPd, newIds)
  }

  /** Adds a single part with subacset `acs`, returns its id. */
  def addPart(acset: ACSet): (Parts, Id) = {
    val (p, newIds) = addParts(Seq(acset))
    (p, newIds(0))
  }

  /** Set the subacset corresponding to `i` to `acs` */
  def setAcset(i: Id, acs: ACSet): Parts = {
    this.copy(
      acsets = acsets + (i -> acs)
    )
  }

  /** Remove the part with id `i` */
  def remPart(i: Id) = {
    this.copy(
      ids = ids.filterNot(_ == i),
      acsets = acsets.filterNot(_._1 == i)
    )
  }

  /** Move the id `i` to the front of the list of ids.
    *
    * This is used, for instance, when dragging a sprite so that the sprite goes
    * over the other parts.
    */
  def moveFront(i: Id) = {
    this.copy(
      ids = ids.filterNot(_ == i) :+ i
    )
  }
}

/** The part corresponding to the top-level acset itself. */
val ROOT = Part(Seq())

/** The empty schema. */
case object SchEmpty extends Schema {
  val obs = Seq()
  val homs = Seq()
  val attrs = Seq()
}

/** A nested acset.
  *
  * @param schema
  *   the schema that the acset conforms to
  *
  * @param props
  *   the top-level properties. The values of morphisms, attributes, and generic
  *   [[Property]]s are stored here. We don't need what in Catlab we call
  *   "subparts"; it's folded into this. For instance, if this is the subacset
  *   for an edge, then the source and target are stored here.
  *
  * @param partsMap
  *   the `Parts` object for each `Ob` in the schema. This is where the
  *   subacsets are stored.
  */
case class ACSet(
    schema: Schema,
    props: PropMap,
    partsMap: Map[Ob, Parts]
) {

  /** Get the subacset corresponding to a nested part; error if invalid */
  def subacset(p: Part): ACSet = trySubacset(p).get

  /** Get the subacset corresponding to a nested part; return None if invalid */
  def trySubacset(p: Part): Option[ACSet] = p.path match {
    case Nil => Some(this)
    case (x, i) :: rest =>
      partsMap
        .get(x)
        .flatMap(_.acsets.get(i).flatMap(_.trySubacset(Part(rest))))
  }

  /** Check if a nested part exists in the ACSet */
  def hasPart(p: Part): Boolean = p.path match {
    case Nil => true
    case (x, i) :: rest =>
      (for {
        parts <- partsMap.get(x)
        sub <- parts.acsets.get(i)
        res <- Some(sub.hasPart(Part(rest)))
      } yield res).getOrElse(false)
  }

  /** Set the subacset for a nested part */
  def setSubacset(p: Part, acs: ACSet): ACSet = p.path match {
    case Nil => {
      acs
    }
    case (x, i) :: rest => {
      val parts = partsMap(x)
      this.copy(
        partsMap = partsMap + (x -> (parts
          .setAcset(i, parts.acsets(i).setSubacset(Part(rest), acs))))
      )
    }
  }

  /** Return all of the parts of the subacset at `i` with type `x`, along with
    * their corresponding subacsets.
    */
  def parts(i: Part, x: Ob): Seq[(Part, ACSet)] = {
    val ps = subacset(i).partsMap(x)
    ps.ids.map(id => (i.extend(x, id), ps.acsets(id)))
  }

  /** Return all of the parts of the subacset at `i` with type `x`, without
    * subacsets
    */
  def partsOnly(i: Part, x: Ob): Seq[Part] = {
    val ps = subacset(i).partsMap(x)
    ps.ids.map(id => i.extend(x, id))
  }

  /** Get the value of `f` at the part `i`; errors if unset. */
  def subpart(f: Property, i: Part): f.Value = subacset(i).props(f)

  /** Get the value of `f` at the part `i`; returns `None` if unset. */
  def trySubpart(f: Property, i: Part): Option[f.Value] =
    trySubacset(i).flatMap(_.props.get(f))

  /** Check if the part `i` has property `f` */
  def hasSubpart(f: Property, i: Part) = trySubpart(f, i) match
    case Some(j) => true
    case None    => false

  /** Adds a part of type `x` to the subacset at `p` with initial subacset
    * `init`
    */
  def addPart(p: Part, x: Ob, init: ACSet): (ACSet, Part) = {
    val sub = subacset(p)
    val subschema = schema.subschema(p.ty.asInstanceOf[PartType].extend(x))
    val (newparts, i) = sub.partsMap(x).addPart(init)
    val newSub = sub.copy(
      partsMap = sub.partsMap + (x -> newparts)
    )
    (setSubacset(p, newSub), p.extend(x, i))
  }

  /** Add several parts of type `x` to the subacset at `p` with initial
    * subacsets given by inits.
    */
  def addParts(p: Part, x: Ob, inits: Seq[ACSet]): (ACSet, Seq[Part]) = {
    val sub = subacset(p)
    val subschema = schema.subschema(p.ty.asInstanceOf[PartType].extend(x))
    val (newparts, ids) = sub.partsMap(x).addParts(inits)
    val newSub = sub.copy(
      partsMap = sub.partsMap + (x -> newparts)
    )
    (setSubacset(p, newSub), ids.map(i => p.extend(x, i)))
  }

  /** Convenience overload of [[addParts]] */
  def addPartsProps(p: Part, x: Ob, props: Seq[PropMap]): (ACSet, Seq[Part]) = {
    val subschema = schema.subschema(p.ty.asInstanceOf[PartType].extend(x))
    addParts(p, x, props.map(p => ACSet(subschema, p)))
  }

  /** Convenience overload of [[addPart]] */
  def addPart(p: Part, x: Ob, props: PropMap): (ACSet, Part) = {
    val subschema = schema.subschema(p.ty.asInstanceOf[PartType].extend(x))
    addPart(p, x, ACSet(subschema, props))
  }

  /** Convenience overload of [[addPart]] */
  def addPart(x: Ob, props: PropMap): (ACSet, Part) = addPart(ROOT, x, props)

  /** Convenience overload of [[addPart]] */
  def addPart(x: Ob, init: ACSet): (ACSet, Part) = addPart(ROOT, x, init)

  /** Convenience overload of [[addPart]] */
  def addPart(p: Part, x: Ob): (ACSet, Part) = addPart(p, x, PropMap())

  /** Convenience overload of [[addPart]] */
  def addPart(x: Ob): (ACSet, Part) = addPart(ROOT, x, PropMap())

  /** Move the part `p` to the front of its parent `Parts`. See
    * [[Parts.moveFront]].
    */
  def moveFront(p: Part): ACSet = {
    assert(p.path.length > 0)
    val (prefix, (x, i)) = (Part(p.path.dropRight(1)), p.path.last)
    val sub = subacset(prefix)
    val newsub = sub.copy(
      partsMap = sub.partsMap + (x -> sub.partsMap(x).moveFront(i))
    )
    setSubacset(prefix, newsub)
  }

  /** Set the property `f` of part `p` to `v` */
  def setSubpart(p: Part, f: Property, v: f.Value): ACSet = {
    val sub = subacset(p)
    val newSub = sub.copy(
      props = sub.props.set(f, v)
    )
    setSubacset(p, newSub)
  }

  /** Unset the property `f` of `p` */
  def remSubpart(p: Part, f: Property): ACSet = {
    val sub = subacset(p)
    val newSub = sub.copy(
      props = sub.props - f
    )
    setSubacset(p, newSub)
  }

  /** Return sequence of the parts that have property `f` set to `p` */
  def incident(p: Part, f: Hom): Seq[Part] = {
    val codom = f.codoms
      .find(c => p.ty.path.drop(p.ty.path.length - c.path.length) == c.path)
      .get
    val prefix = Part(p.path.dropRight(codom.path.length))

    /** Essentially, we look at all parts with part type f.dom, and filter which
      * ones have a property f set to p
      */
    def helper(acs: ACSet, part: Part, remaining: Seq[Ob]): Seq[Part] =
      remaining match {
        case Nil => if acs.props.get(f) == Some(p) then Seq(part) else Seq()
        case ob :: rest =>
          acs
            .partsMap(ob)
            .acsets
            .toSeq
            .flatMap((i, acs) => helper(acs, part.extend(ob, i), rest))
      }

    f.doms.flatMap(dom => helper(subacset(prefix), prefix, dom.path))
  }

  /** Remove a part, but not any of the other parts that might refer to it. */
  def remPartOnly(p: Part): ACSet = {
    if hasPart(p) then {
      assert(p.path.length > 0)
      val (pre, (x, i)) = (p.path.dropRight(1), p.path.last)
      val sub = subacset(Part(pre))
      val newSub = sub.copy(
        partsMap = sub.partsMap + (x -> sub.partsMap(x).remPart(i))
      )
      setSubacset(Part(pre), newSub)
    } else {
      this
    }
  }

  /** Remove a part and all of the other parts that refer to it. */
  def remPart(p: Part): ACSet = {
    val visited = mutable.Set[Part]()
    val queue = mutable.Queue[Part](p)
    while (!queue.isEmpty) {
      val q = queue.dequeue()
      visited.add(q)
      for ((_, f) <- schema.homsInto(q.ty)) {
        queue.enqueueAll(
          incident(q, f)
            .filter(!visited.contains(_))
        )
      }
      val sub = subacset(q)
      for (ob <- sub.schema.obs) {
        queue.enqueueAll(parts(q, ob).map(_._1).filter(!visited.contains(_)))
      }
    }
    val toRemove = visited.toSeq
    toRemove.foldLeft(this)(_.remPartOnly(_))
  }

  /** Remove all of the parts in `ps` */
  def remParts(ps: Seq[Part]): ACSet = ps match
    case Seq()             => this
    case Seq(p, rest @ _*) => this.remPart(p).remParts(rest)

  /** Add the properties in `newProps` to the top-level properties. */
  def addProps(newProps: PropMap): ACSet = {
    this.copy(props = props ++ newProps)
  }
}

/** This object contains the constructor method for ACSets and also a collection
  * of wrappers around ACSet methods in the `State` monad that allow for a
  * quasi-imperative API for modifying ACSets purely.
  */
object ACSet {

  /** Construct a new ACSet with schema `s` */
  def apply(s: Schema): ACSet = ACSet(s, PropMap())

  /** Construct a new ACSet with schema `s` and top-level parts `props` */
  def apply(s: Schema, props: PropMap): ACSet =
    new ACSet(s, props, s.obs.map(ob => ob -> Parts(0, Seq(), Map())).toMap)

  /** `State` wrapper around ACSet.addParts */
  def addParts(p: Part, x: Ob, props: Seq[PropMap]): State[ACSet, Seq[Part]] =
    State(_.addPartsProps(p, x, props))

  /** `State` wrapper around ACSet.addPart */
  def addPart(p: Part, x: Ob, props: PropMap): State[ACSet, Part] =
    State(_.addPart(p, x, props))

  /** `State` wrapper around ACSet.addPart */
  def addPart(p: Part, x: Ob): State[ACSet, Part] =
    State(_.addPart(p, x))

  /** `State` wrapper around ACSet.addPart */
  def addPart(x: Ob, props: PropMap): State[ACSet, Part] = State(
    _.addPart(x, props)
  )

  /** `State` wrapper around ACSet.addPart */
  def addPart(x: Ob, init: ACSet): State[ACSet, Part] = State(
    _.addPart(x, init)
  )

  /** `State` wrapper around ACSet.addPart */
  def addPart(x: Ob): State[ACSet, Part] = State(_.addPart(x, PropMap()))

  /** `State` wrapper around ACSet.setSubpart */
  def setSubpart(p: Part, f: Property, v: f.Value): State[ACSet, Unit] =
    State.modify(_.setSubpart(p, f, v))

  /** `State` wrapper around ACSet.remSubpart */
  def remSubpart(p: Part, f: Property): State[ACSet, Unit] =
    State.modify(_.remSubpart(p, f))

  /** `State` wrapper around ACSet.remPart */
  def remPart(p: Part): State[ACSet, Unit] = State.modify(_.remPart(p))

  /** `State` wrapper around ACSet.remParts */
  def remParts(ps: Seq[Part]): State[ACSet, Unit] = State.modify(_.remParts(ps))

  /** `State` wrapper around ACSet.moveFront */
  def moveFront(p: Part): State[ACSet, Unit] = State.modify(_.moveFront(p))

  /** Returns a lens into the value of the property `f` for part `x` */
  def subpartLens(f: Property, x: Part) =
    Lens[ACSet, f.Value](_.subpart(f, x))(y => s => s.setSubpart(x, f, y))

}

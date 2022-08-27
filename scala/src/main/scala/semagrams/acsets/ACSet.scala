package semagrams.acsets

import monocle.syntax.all._
import monocle.macros.GenLens
import monocle.macros.GenIso
import monocle._
import monocle.function.Index
import cats.implicits._
import cats.data.State

import semagrams._
import java.awt.Canvas
import cats.syntax.validated
import semagrams.acsets.ACSet
import cats.kernel.compat.scalaVersionSpecific
import cats.Monad
import cats.Traverse
import scala.collection.mutable

/**
 * We use a somewhat hacky mapping of schemas into scala types in order to
 * provide typed wrappers around fundamentally untyped acsets. This involves
 * type-casting at runtime, so it is not totally type-safe. However, the
 * types of methods and functions on acsets are useful for *other* code.
 *
 * That is, types do not give us much safety in this file, but they do give
 * safety elsewhere, as long as the acsets are consistently accessed through
 * the typed wrapper interfaces. We do not lose performance through this
 * strategy because javascript is untyped anyways.
 */

/**
 * Objects of schema categories are given by singleton types that extend Ob.
 */
abstract class AbstractOb extends EntityType

type Ob = AbstractOb & Singleton

extension (ent: Entity)
  def asElt[X <: Ob: ValueOf] = if (ent.entityType == valueOf[X]) then Some(ent.asInstanceOf[Elt[X]]) else None

/**
 * In the mapping of schemas into scala types, to encode morphisms
 * we use two abstract classes. A morphism in a schema category is given
 * by a singleton type that extends Hom[Dom,Codom], where Dom and Codom
 * are the singleton types of the domain and codomain.
 *
 * We then have a superclass of all homs, so that we can have containers
 * whose elements could be homs of different types.
 */
abstract class AbstractHom {
  def dom: Ob
  def codom: Ob
}

abstract class Hom[Dom <: Ob: ValueOf, Codom <: Ob: ValueOf] extends AbstractHom {
  def dom = valueOf[Dom]
  def codom = valueOf[Codom]
}

/**
 * A similar strategy to [[semagrams.acsets.AbstractHom]] is used
 * for AbstractAttrs, except the codomain is any scala type. Then an
 * attribute for a integer valued weight on graph edges might be declared
 * as
 *
 * ```scala
 * case class Weight[T]() extends Attr[E,T]
 * ```
 */
abstract class AbstractAttr
abstract class Attr[Dom <: Ob, Codom] extends AbstractAttr

/**
 * A Schema is simply a bunch of object, morphisms, and attributes.
 *
 * Note that we do not have attribute types like in Julia; rather
 * attributes themselves are typed by their codomain type.
 *
 * N.B. maybe we should change this?
 */
case class Schema(obs: List[Ob], homs: List[AbstractHom], attrs: List[AbstractAttr])

object Schema {
  /**
   * A convenience constructor that simply takes in all of the generators
   * and splits them out for the user.
   */
  def apply(args: (Ob | AbstractHom | AbstractAttr)*) = {
    val obs = args.collect {
      case (x: AbstractOb) => x
    }
    val homs = args.collect {
      case (f: AbstractHom) => f
    }
    val attrs = args.collect {
      case (f: AbstractAttr) => f
    }
    new Schema(obs.toList.map(_.asInstanceOf[Ob]), homs.toList, attrs.toList)
  }
}

/**
 * This is a member of the category of elements of an acset.
 * It extends [[semagrams.Entity]], which means that it can be used
 * as a key for sprites in Semagrams.
 */
case class Elt[X <: Ob: ValueOf](id: Int) extends Entity {
  def entityType = valueOf[X]
}

/**
 * This is the data of an acset, represented dynamically.
 *
 * A BareACSet by itself does not have a schema; a schema must
 * be passed in for each of its methods.
 *
 * Type checks against this schema are done at runtime to validate
 * the methods.
 *
 * For simplicity, we use unindexed hashmaps to store the action of the ACSet
 * on the morphisms in the schema, and we use unique integers for each
 * element in the category of elements of the ACSet. This massively
 * simplifies the implementation, and we are not concerned about performance
 * because these methods are performed at a time scale of user input,
 * not at a time scale of algorithms.
 *
 * If this assumption changes, we may have to revisit this decision,
 * but this is good for now.
 *
 * Also, notice that this is purely functional; methods that modify the acset
 * instead return a new acset.
 */
case class BareACSet(
  nextId: Int,
  obs: Map[Ob, Set[Entity]],
  homs: Map[AbstractHom, Map[Entity, Entity]],
  attrs: Map[AbstractAttr, Map[Entity, Any]]
) {
  def parts[X <: Ob](s: Schema, ob: X): Set[Elt[X]] = {
    assert(s.obs contains ob)
    obs(ob).asInstanceOf[Set[Elt[X]]]
  }

  def addPart[X <: Ob: ValueOf](s: Schema, ob: X): (Elt[X], BareACSet) = {
    assert(s.obs contains ob)
    val e = Elt[X](nextId)
    (
      e,
      this.copy(
        nextId = nextId + 1,
        obs = obs + (ob -> (obs(ob) + e))
      )
    )
  }

  def subpart[X <: Ob, Y <: Ob](s: Schema, f: Hom[X, Y], x: Elt[X]): Option[Elt[Y]] = {
    assert(s.homs contains f)
    homs(f).asInstanceOf[Map[Elt[X], Elt[Y]]].get(x)
  }

  def subpart[X <: Ob, T](s: Schema, f: Attr[X, T], x: Elt[X]): Option[T] = {
    assert(s.attrs contains f)
    attrs(f).asInstanceOf[Map[Elt[X], T]].get(x)
  }

  /**
   * We use an implicit in order to pass in the singleton instance of the type X
   * so that we can look it up in the _obs map.
   */
  def incident[X <: Ob: ValueOf, Y <: Ob](s: Schema, f: Hom[X, Y], y: Elt[Y]): Set[Elt[X]] = {
    val xob = valueOf[X]
    assert(s.homs contains f)
    val f_map = homs(f).asInstanceOf[Map[Elt[X], Elt[Y]]]
    obs(xob).asInstanceOf[Set[Elt[X]]].filter(x => f_map(x) == y)
  }

  def setSubpart[X <: Ob, Y <: Ob](s: Schema, f: Hom[X, Y], x: Elt[X], y: Elt[Y]): BareACSet = {
    assert(s.homs contains f)
    this.copy(
      homs = homs + (f -> (homs(f) + (x -> y)))
    )
  }

  def setSubpart[X <: Ob, T](s: Schema, f: Attr[X, T], x: Elt[X], y: T): BareACSet = {
    assert(s.attrs contains f)
    this.copy(
      attrs = attrs + (f -> (attrs(f) + (x -> y)))
    )
  }

  /**
   * Strategy: do a traversal of the undirected graph of the category of elements
   * starting at x, and then delete everything that we touch.
   */
  def remPart[X <: Ob: ValueOf](s: Schema, x: Elt[X]): BareACSet = {
    val visited = mutable.HashSet[Entity]()
    val next = mutable.Stack[Entity](x)
    while (!next.isEmpty) {
      val y = next.pop()
      if (!(visited contains y)) {
        visited.add(y)
        val yob = y.entityType.asInstanceOf[Ob]
        for (f <- s.homs) {
          if (f.codom == yob) {
            for (z <- obs(f.dom)) {
              if (homs(f).get(z) == Some(y)) {
                next.push(z)
              }
            }
          }
        }
      }
    }
    this.copy(
      obs = obs.mapValues(_.filter(y => !(visited contains y))).toMap,
      homs = homs.mapValues(_.filter((k,v) => !(visited contains k) && !(visited contains v))).toMap,
      attrs = attrs.mapValues(_.filter((k,v) => !(visited contains k))).toMap,
    )
  }

  /**
   * This is a version of remPart that does not remove parts that are related
   * to x
   */
  def remPartOnly[X <: Ob: ValueOf](s: Schema, x: Elt[X]): BareACSet = {
    val xob = valueOf[X]
    this.copy(
      obs = obs + (xob -> (obs(xob) - x)),
      homs = homs.map((f,vals) => (f, vals.filter((a,b) => a != x && b != x))),
      attrs = attrs.map((f,vals) => (f, vals.filter((a,b) => a != x)))
    )
  }
}

object BareACSet {
  /**
   * This makes a new
   */
  def apply(s: Schema) = {
    new BareACSet(
      0,
      s.obs.map(ob => (ob -> Set[Entity]())).toMap,
      s.homs.map(f => (f -> Map[Entity, Entity]())).toMap,
      s.attrs.map(f => (f -> Map[Entity, Any]())).toMap,
    )
  }
}

/**
 * To work with ACSets in a typed way, we use the following encoding into
 * Scala's type system.
 *
 * A typed ACSet is essentially a newtype wrapper around a BareACSet, i.e.
 *
 * ```scala
 * case class WeightedGraph[T](acset: BareACSet)
 * ```
 *
 * Then to use this newtype wrapper, one declares a trait implementation
 * that has an Iso between the newtype wrapper and a BareACSet (essentially
 * witnessing that the type is in fact just a wrapper around a BareACSet)
 * and then also provide a schema that should be used for all operations.
 *
 * For instance,
 *
 * ```scala
 * given weightedGraphACSet[T]: ACSet[WeightedGraph[T]] with
 *   val bare = GenIso[WeightedGraph[T], BareACSet]
 *   val schema = Schema(
 *     E(), V(),
 *     Src(), Tgt(),
 *     Weight[T]()
 *   )
 * ```
 *
 * This trait then provides extension methods for the newtype wrapper that
 * pass in the schema to the functions on the BareACSet.
 */
trait ACSet[A] {
  val bare: Iso[A, BareACSet]
  val schema: Schema

  def empty: A = {
    bare.reverseGet(BareACSet(schema))
  }

  extension(a:A)
    def parts[X <: Ob](ob: X): Set[Elt[X]] = {
      bare.get(a).parts(schema, ob)
    }

    def addPart[X <: Ob: ValueOf](ob: X): (Elt[X], A) = {
      bare.modifyF(_.addPart(schema, valueOf[X]))(a)
    }

    def subpart[X <: Ob, Y <: Ob](f: Hom[X, Y], x: Elt[X]): Option[Elt[Y]] = {
      bare.get(a).subpart(schema, f, x)
    }

    def subpart[X <: Ob, T](f: Attr[X, T], x: Elt[X]): Option[T] = {
      bare.get(a).subpart(schema, f, x)
    }

    def incident[X <: Ob: ValueOf, Y <: Ob](f: Hom[X,Y], y: Elt[Y]): Set[Elt[X]] = {
      bare.get(a).incident(schema, f, y)
    }

    def setSubpart[X <: Ob, Y <: Ob](f: Hom[X, Y], x: Elt[X], y: Elt[Y]): A = {
      bare.modify(_.setSubpart(schema, f, x, y))(a)
    }

    def setSubpart[X <: Ob, T](f: Attr[X, T], x: Elt[X], y: T): A = {
      bare.modify(_.setSubpart(schema, f, x, y))(a)
    }

    def updateSubpart[X <: Ob, T](f: Attr[X, T], x: Elt[X], fun: T => T): A = {
      bare.modify(s =>
        {
          val prev = s.subpart(schema, f, x)
          prev.map(v => s.setSubpart(schema, f, x, fun(v))).getOrElse(s)
        }
      )(a)
    }

    def remPart[X <: Ob: ValueOf](x: Elt[X]): A = {
      bare.modify(_.remPart(schema, x))(a)
    }
}

/**
 * Finally, we also provide wrappers around the mutating methods on an acset
 * that do the mutation in the state monad, so that we can write acset
 * mutation in an imperative way.
 */

def addPart[A: ACSet, X <: Ob: ValueOf](ob: X): State[A, Elt[X]] =
  State(_.addPart(valueOf[X]).swap)

def setSubpart[A: ACSet, X <: Ob, Y <: Ob](f: Hom[X, Y], x: Elt[X], y: Elt[Y]): State[A,Unit] =
  State.modify(_.setSubpart(f, x, y))

def setSubpart[A: ACSet, X <: Ob, T](f: Attr[X, T], x: Elt[X], y: T): State[A,Unit] =
  State.modify(_.setSubpart(f, x, y))

def remPart[A: ACSet, X <: Ob: ValueOf](x: Elt[X]): State[A, Unit] =
  State.modify(_.remPart(x))

package urldsl.language

import urldsl.errors.{DummyError, ErrorFromThrowable, PathMatchingError, SimplePathMatchingError}
import urldsl.parsers.{UrlStringParser, UrlStringParserGenerator}
import urldsl.vocabulary._

import scala.language.implicitConversions

/**
  * Represents a part of the path string of an URL, containing an information of type T, or an error of type A.
  * @tparam T type represented by this PathSegment
  * @tparam A type of the error that this PathSegment produces on "illegal" url paths.
  */
trait PathSegment[T, A] {

  /**
    * Tries to match the list of [[urldsl.vocabulary.Segment]]s to create an instance of `T`.
    * If it can not, it returns an error indicating the reason of the failure.
    * If it could, it returns the value of `T`, as well as the list of unused segments.
    *
    * @example
    *          For example, a segment that matches simply a String in the first segment, when giving segments like
    *          List(Segment("hello"), Segment("3"))
    *          will return
    *          Right(PathMatchOutput("hello", List(Segment("3")))
    *
    * @param segments The list of [[urldsl.vocabulary.Segment]] to match this path segment again.
    * @return The "de-serialized" element with unused segment, if successful.
    */
  def matchSegments(segments: List[Segment]): Either[A, PathMatchOutput[T]]

  def matchRawUrl[UrlParser <: UrlStringParser](
      url: String
  )(implicit urlStringParser: UrlStringParserGenerator[UrlParser]): Either[A, PathMatchOutput[T]] =
    matchSegments(urlStringParser.parser(url).segments)

  def matchPath(path: String): Either[A, PathMatchOutput[T]] =
    matchSegments(Segment.fromPath(path))

  /**
    * Generate a list of segments representing the argument `t`.
    *
    * `matchSegments` and `createSegments` should be (functional) inverse of each other. That is,
    * `this.matchSegments(this.createSegments(t)) == Right(PathMathOutput(t, Nil))`
    */
  def createSegments(t: T): List[Segment]

  /**
    * Sugar when `T =:= Unit`
    */
  final def createSegments()(implicit ev: Unit =:= T): List[Segment] = createSegments(ev(()))

  /**
    * Concatenates the segments generated by `createSegments`
    */
  def createPath(t: T): String = createSegments(t).map(_.content).mkString("/")

  /**
    * Sugar when `T =:= Unit`
    */
  final def createPath()(implicit ev: Unit =:= T): String = createPath(())

  /**
    * Concatenates `this` [[PathSegment]] with `that` one, "tupling" the types with the [[Tupler]] rules.
    */
  final def /[U](that: PathSegment[U, A])(implicit ev: Tupler[T, U]): PathSegment[ev.Out, A] =
    PathSegment.factory[ev.Out, A](
      (segments: List[Segment]) =>
        for {
          firstOut <- this.matchSegments(segments)
          PathMatchOutput(t, remaining) = firstOut
          secondOut <- that.matchSegments(remaining)
          PathMatchOutput(u, lastRemaining) = secondOut
        } yield PathMatchOutput(ev(t, u), lastRemaining),
      (out: ev.Out) => {
        val (t, u) = ev.unapply(out)

        this.createSegments(t) ++ that.createSegments(u)
      }
    )

  final def ?[ParamsType, QPError](
      params: QueryParameters[ParamsType, QPError]
  ): PathSegmentWithQueryParams[T, A, ParamsType, QPError] =
    new PathSegmentWithQueryParams(this, params)

  /**
    * Adds an extra satisfying criteria to the de-serialized output of this [[PathSegment]].
    *
    * The new de-serialization works as follows:
    * - if the initial de-serialization fails, then it returns the generated error
    * - otherwise, if the de-serialized element satisfies the predicate, then it returns the element
    * - if the predicate is false, generates the given `error` by feeding it the segments that it tried to match.
    *
    * This can be useful in, among others, two scenarios:
    * - enforce bigger restriction on a segment (e.g., from integers to positive integer, regex match...)
    * - in a multi-part segment, ensure consistency between the different component (e.g., a range of two integers that
    *   should not be too large...)
    */
  final def filter(predicate: T => Boolean, error: List[Segment] => A): PathSegment[T, A] = PathSegment.factory[T, A](
    (segments: List[Segment]) =>
      matchSegments(segments)
        .filterOrElse(((_: PathMatchOutput[T]).output).andThen(predicate), error(segments)),
    createSegments
  )

  /**
    * Builds a [[PathSegment]] that first tries to match with this one, then tries to match with `that` one.
    * If both fail, the error of the second is returned (todo[behaviour]: should that change?)
    */
  final def ||[U](that: PathSegment[U, A]): PathSegment[Either[T, U], A] = PathSegment.factory[Either[T, U], A](
    segments =>
      this.matchSegments(segments) match {
        case Right(output) => Right(PathMatchOutput(Left(output.output), output.unusedSegments))
        case Left(_) =>
          that.matchSegments(segments).map(output => PathMatchOutput(Right(output.output), output.unusedSegments))
      },
    _.fold(this.createSegments, that.createSegments)
  )

  /**
    * Casts this [[PathSegment]] to the new type U. Note that the [[urldsl.vocabulary.Codec]] must be an exception-free
    * bijection between T and U.
    */
  final def as[U](implicit codec: Codec[T, U]): PathSegment[U, A] = PathSegment.factory[U, A](
    (matchSegments _).andThen(_.map(_.map(codec.leftToRight))),
    (codec.rightToLeft _).andThen(createSegments)
  )

}

object PathSegment {

  type PathSegmentSimpleError[T] = PathSegment[T, SimplePathMatchingError]

  /**
    * A Type of path segment where we don't care about the error.
    */
  type PathSegmentNoError[T] = PathSegment[T, DummyError]

  /** Trait factory */
  def factory[T, A](
      matching: List[Segment] => Either[A, PathMatchOutput[T]],
      creating: T => List[Segment]
  ): PathSegment[T, A] = new PathSegment[T, A] {
    def matchSegments(segments: List[Segment]): Either[A, PathMatchOutput[T]] = matching(segments)

    def createSegments(t: T): List[Segment] = creating(t)
  }

  /** Simple path segment that matches everything by passing segments down the line. */
  final def empty[A]: PathSegment[Unit, A] =
    factory[Unit, A](segments => Right(PathMatchOutput((), segments)), _ => Nil)
  final def root[A]: PathSegment[Unit, A] = empty

  /**
    * Simple trait factory for "single segment"-oriented path Segments.
    *
    * This can be used to match a simple String, or a simple Int, etc...
    */
  final def simplePathSegment[T, A](matching: Segment => Either[A, T], creating: T => Segment)(
      implicit pathMatchingError: PathMatchingError[A]
  ): PathSegment[T, A] =
    factory(
      (_: Seq[Segment]) match {
        case Nil           => Left(pathMatchingError.missingSegment)
        case first :: rest => matching(first).map(PathMatchOutput(_, rest))
      },
      List(_).map(creating)
    )

  /** Matches a simple String and returning it. */
  final def stringSegment[A](implicit pathMatchingError: PathMatchingError[A]): PathSegment[String, A] =
    segment[String, A]

  /** Matches a simple Int and tries to convert it to an Int. */
  final def intSegment[A](
      implicit pathMatchingError: PathMatchingError[A],
      fromThrowable: ErrorFromThrowable[A]
  ): PathSegment[Int, A] = segment[Int, A]

  /**
    * Creates a segment matching any element of type `T`, as long as the [[urldsl.vocabulary.FromString]] can
    * de-serialize it.
    */
  final def segment[T, A](
      implicit fromString: FromString[T, A],
      printer: Printer[T],
      error: PathMatchingError[A]
  ): PathSegment[T, A] = simplePathSegment[T, A]((fromString.apply _).compose(_.content), printer.print)

  /**
    * Check that the segments ends at this point.
    */
  final def endOfSegments[A](implicit pathMatchingError: PathMatchingError[A]): PathSegment[Unit, A] = factory[Unit, A](
    (_: List[Segment]) match {
      case Nil => Right(PathMatchOutput((), Nil))
      case ss  => Left(pathMatchingError.endOfSegmentRequired(ss))
    },
    _ => Nil
  )

  /**
    * Consumes all the remaining segments.
    *
    * This can be useful for static resources.
    */
  final def remainingSegments[A]: PathSegment[List[String], A] = factory[List[String], A](
    segments => Right(PathMatchOutput(segments.map(_.content), Nil)),
    _.map(Segment.apply)
  )

  /**
    * Returns a [[PathSegment]] which matches exactly the argument `t`.
    *
    * This conversion is implicit if you can provide a [[FromString]] and a [[Printer]], so that it enables writing,
    * e.g.,
    * `root / "hello" / true`
    */
  implicit final def unaryPathSegment[T, A](
      t: T
  )(
      implicit fromString: FromString[T, A],
      printer: Printer[T],
      pathMatchingError: PathMatchingError[A]
  ): PathSegment[Unit, A] =
    simplePathSegment(
      s =>
        fromString(s.content)
          .filterOrElse[A](_ == t, pathMatchingError.wrongValue(printer(t), s.content))
          .map(_ => ()),
      (_: Unit) => Segment(printer(t))
    )

  final lazy val dummyErrorImpl = PathSegmentImpl[DummyError]
  final lazy val simplePathErrorImpl = PathSegmentImpl[SimplePathMatchingError]

}
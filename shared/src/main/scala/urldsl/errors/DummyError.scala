package urldsl.errors
import urldsl.vocabulary.Segment

/**
  * Error type with only one instance, for when you only care about knowing whether the error exists.
  */
sealed trait DummyError

object DummyError {

  final val dummyError: DummyError = new DummyError {}

  implicit final lazy val dummyErrorIsParamMatchingError: ParamMatchingError[DummyError] =
    new ParamMatchingError[DummyError] {
      def missingParameterError(paramName: String): DummyError = dummyError

      def fromThrowable(throwable: Throwable): DummyError = dummyError
    }

  implicit final lazy val dummyErrorIsPathMatchingError: PathMatchingError[DummyError] =
    new PathMatchingError[DummyError] {
      def malformed(str: String): DummyError = dummyError

      def endOfSegmentRequired(remainingSegments: List[Segment]): DummyError = dummyError

      def wrongValue(expected: String, actual: String): DummyError = dummyError

      def missingSegment: DummyError = dummyError

      def fromThrowable(throwable: Throwable): DummyError = dummyError
    }

  implicit final lazy val dummyErrorIsFromThrowable: ErrorFromThrowable[DummyError] = (_: Throwable) => dummyError

}
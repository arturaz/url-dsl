package urldsl.url

import urldsl.vocabulary.{Param, Segment}

trait UrlStringGenerator {

  def encode(str: String, encoding: String = "utf-8"): String

  def makePath(segments: List[Segment]): String =
    segments.map(_.content).map(encode(_)).filter(_.nonEmpty).mkString("/")

  def makeParamsMap(params: Map[String, Param]): Map[String, List[String]] =
    params
      .map { case (key, value) => key -> value.content.map(encode(_)) }

  def makeParams(params: Map[String, Param]): String =
    makeParamsMap(params)
      .flatMap { case (key, values) => values.map(value => s"$key=$value") }
      .mkString("&")

  final def makeUrl(segments: List[Segment], params: Map[String, Param]): String = {
    val paramsString = makeParams(params)
    val pathString = makePath(segments)

    pathString + (if (paramsString.nonEmpty) "?" else "") + pathString
  }

}

object UrlStringGenerator extends DefaultUrlStringGenerator

package xitrum.routing

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import scala.reflect.runtime.universe
import scala.util.control.NonFatal

import org.objectweb.asm.{AnnotationVisitor, ClassReader, ClassVisitor, Opcodes, Type}
import sclasner.{FileEntry, Scanner}

import xitrum.{Action, Logger, SockJsActor}
import xitrum.annotation._
import xitrum.sockjs.SockJsPrefix

case class DiscoveredAcc(
  normalRoutes:              SerializableRouteCollection,
  sockJsWithoutPrefixRoutes: SerializableRouteCollection,
  sockJsMap:                 Map[String, SockJsClassAndOptions],
  swaggerMap:                Map[Class[_ <: Action], Swagger]
)

/** Scan all classes to collect routes from actions. */
class RouteCollector extends Logger {
  def deserializeCacheFileOrRecollect(cachedFileName: String): DiscoveredAcc = {
    var acc = DiscoveredAcc(
      new SerializableRouteCollection,
      new SerializableRouteCollection,
      Map[String, SockJsClassAndOptions](),
      Map[Class[_ <: Action], Swagger]()
    )

    val actionTreeBuilder = Scanner.foldLeft(cachedFileName, new ActionTreeBuilder, discovered _)
    val ka                = actionTreeBuilder.getConcreteActionsAndAnnotations
    ka.foreach { case (klass, annotations) =>
      acc = processAnnotations(acc, klass, annotations)
    }

    acc
  }

  //----------------------------------------------------------------------------

  private def discovered(acc: ActionTreeBuilder, entry: FileEntry): ActionTreeBuilder = {
    try {
      if (entry.relPath.endsWith(".class")) {
        val reader = new ClassReader(entry.bytes)
        acc.addBranches(reader.getClassName, reader.getInterfaces)
      } else {
        acc
      }
    } catch {
      case NonFatal(e) =>
        logger.warn("Could not scan route for " + entry.relPath + " in " + entry.container, e)
        acc
    }
  }

  private def processAnnotations(
      acc:         DiscoveredAcc,
      klass:       Class[_ <: Action],
      annotations: ActionAnnotations
  ): DiscoveredAcc = {
    val className  = klass.getName
    val fromSockJs = className.startsWith(classOf[SockJsPrefix].getPackage.getName)
    val routes     = if (fromSockJs) acc.sockJsWithoutPrefixRoutes else acc.normalRoutes

    collectNormalRoutes(routes, className, annotations)
    val newSockJsMap = collectSockJsMap(acc.sockJsMap, className, annotations)
    collectErrorRoutes(routes, className, annotations)

    val newSwaggerMap = collectSwagger(annotations) match {
      case None          => acc.swaggerMap
      case Some(swagger) => acc.swaggerMap + (klass -> swagger)
    }

    DiscoveredAcc(acc.normalRoutes, acc.sockJsWithoutPrefixRoutes, newSockJsMap, newSwaggerMap)
  }

  private def collectNormalRoutes(
      routes:      SerializableRouteCollection,
      className:   String,
      annotations: ActionAnnotations
  ) {
    var routeOrder          = optRouteOrder(annotations.routeOrder)  // -1: first, 1: last, 0: other
    var cacheSecs           = optCacheSecs(annotations.cache)        // < 0: cache action, > 0: cache page, 0: no cache
    var method_pattern_coll = ArrayBuffer[(String, String)]()

    annotations.route.foreach { a =>
      listMethodAndPattern(a).foreach { m_p   => method_pattern_coll.append(m_p) }
    }

    method_pattern_coll.foreach { case (method, pattern) =>
      val compiledPattern   = RouteCompiler.compile(pattern)
      val serializableRoute = new SerializableRoute(method, compiledPattern, className, cacheSecs)
      val coll              = (routeOrder, method) match {
        case (-1, "GET") => routes.firstGETs
        case ( 1, "GET") => routes.lastGETs
        case ( 0, "GET") => routes.otherGETs

        case (-1, "POST") => routes.firstPOSTs
        case ( 1, "POST") => routes.lastPOSTs
        case ( 0, "POST") => routes.otherPOSTs

        case (-1, "PUT") => routes.firstPUTs
        case ( 1, "PUT") => routes.lastPUTs
        case ( 0, "PUT") => routes.otherPUTs

        case (-1, "PATCH") => routes.firstPATCHs
        case ( 1, "PATCH") => routes.lastPATCHs
        case ( 0, "PATCH") => routes.otherPATCHs

        case (-1, "DELETE") => routes.firstDELETEs
        case ( 1, "DELETE") => routes.lastDELETEs
        case ( 0, "DELETE") => routes.otherDELETEs

        case (-1, "OPTIONS") => routes.firstOPTIONSs
        case ( 1, "OPTIONS") => routes.lastOPTIONSs
        case ( 0, "OPTIONS") => routes.otherOPTIONSs

        case (-1, "WEBSOCKET") => routes.firstWEBSOCKETs
        case ( 1, "WEBSOCKET") => routes.lastWEBSOCKETs
        case ( 0, "WEBSOCKET") => routes.otherWEBSOCKETs
      }
      coll.append(serializableRoute)
    }
  }

  private def collectErrorRoutes(
      routes:      SerializableRouteCollection,
      className:   String,
      annotations: ActionAnnotations)
  {
    val tpeError404 = universe.typeOf[Error404]
    val tpeError500 = universe.typeOf[Error500]
    annotations.error.foreach { case a =>
      val tpe = a.tpe
      if (tpe == tpeError404) routes.error404 = Some(className)
      if (tpe == tpeError500) routes.error500 = Some(className)
    }
  }

  private def collectSockJsMap(
      sockJsMap:   Map[String, SockJsClassAndOptions],
      className:   String,
      annotations: ActionAnnotations
  ): Map[String, SockJsClassAndOptions] =
  {
    var pathPrefix: String = null
    annotations.route.foreach { case a =>
      if (a.tpe == universe.typeOf[SOCKJS])
        pathPrefix   = a.scalaArgs(0).productElement(0).asInstanceOf[universe.Constant].value.toString
    }

    if (pathPrefix == null) {
      sockJsMap
    } else {
      val klass        = Class.forName(className).asInstanceOf[Class[SockJsActor]]
      val noWebSocket  = annotations.sockJsNoWebSocket.isDefined
      val cookieNeeded = annotations.sockJsCookieNeeded.isDefined
      sockJsMap + (pathPrefix -> new SockJsClassAndOptions(klass, !noWebSocket, cookieNeeded))
    }
  }

  //----------------------------------------------------------------------------

  /** -1: first, 1: last, 0: other */
  private def optRouteOrder(annotationo: Option[universe.Annotation]): Int = {
    annotationo match {
      case None => 0

      case Some(annotation) =>
        val tpe = annotation.tpe
        if (tpe == universe.typeOf[First])
          -1
        else if (tpe == universe.typeOf[Last])
          1
        else
          0
    }
  }

  /** < 0: cache action, > 0: cache page, 0: no cache */
  private def optCacheSecs(annotationo: Option[universe.Annotation]): Int = {
    annotationo match {
      case None => 0

      case Some(annotation) =>
        val tpe = annotation.tpe

        if (tpe == universe.typeOf[CacheActionDay])
          -annotation.scalaArgs(0).toString.toInt * 24 * 60 * 60
        else if (tpe == universe.typeOf[CacheActionHour])
          -annotation.scalaArgs(0).toString.toInt      * 60 * 60
        else if (tpe == universe.typeOf[CacheActionMinute])
          -annotation.scalaArgs(0).toString.toInt           * 60
        else if (tpe == universe.typeOf[CacheActionSecond])
          -annotation.scalaArgs(0).toString.toInt
        else if (tpe == universe.typeOf[CachePageDay])
          annotation.scalaArgs(0).toString.toInt * 24 * 60 * 60
        else if (tpe == universe.typeOf[CachePageHour])
          annotation.scalaArgs(0).toString.toInt      * 60 * 60
        else if (tpe == universe.typeOf[CachePageMinute])
          annotation.scalaArgs(0).toString.toInt           * 60
        else if (tpe == universe.typeOf[CachePageSecond])
          annotation.scalaArgs(0).toString.toInt
        else
          0
    }
  }

  /** @return Seq[(method, pattern)] */
  private def listMethodAndPattern(annotation: universe.Annotation): Seq[(String, String)] = {
    val tpe = annotation.tpe

    if (tpe == universe.typeOf[GET])
      getStrings(annotation).map(("GET", _))
    else if (tpe == universe.typeOf[POST])
      getStrings(annotation).map(("POST", _))
    else if (tpe == universe.typeOf[PUT])
      getStrings(annotation).map(("PUT", _))
    else if (tpe == universe.typeOf[PATCH])
      getStrings(annotation).map(("PATCH", _))
    else if (tpe == universe.typeOf[DELETE])
      getStrings(annotation).map(("DELETE", _))
    else if (tpe == universe.typeOf[OPTIONS])
      getStrings(annotation).map(("OPTIONS", _))
    else if (tpe == universe.typeOf[WEBSOCKET])
      getStrings(annotation).map(("WEBSOCKET", _))
    else
      Seq()
  }

  private def getStrings(annotation: universe.Annotation): Seq[String] = {
    annotation.scalaArgs.map { tree => tree.productElement(0).asInstanceOf[universe.Constant].value.toString }
  }

  //----------------------------------------------------------------------------

  private def collectSwagger(annotations: ActionAnnotations): Option[Swagger] = {
    annotations.swagger.map { annotation =>
      val scalaArgs = annotation.scalaArgs
      val summary   = scalaArgs.head.productElement(0).asInstanceOf[universe.Constant].value.toString
      val varargs   = scalaArgs.tail

      val swaggerVarargs: Seq[Swagger.ParamOrResponse] = varargs.map { paramOrResponse =>
        if (paramOrResponse.tpe == universe.typeOf[Swagger.Param]) {
          // Ex: List(xitrum.annotation.Swagger.Param.apply, "title", xitrum.annotation.Swagger.Form, xitrum.annotation.Swagger.String, xitrum.annotation.Swagger.Param.apply$default$4)
          val children = paramOrResponse.children

          val name      = children(1).productElement(0).asInstanceOf[universe.Constant].value.toString
          val paramType = children(2).toString
          val valueType = children(3).toString

          val description =
            if (children(4).toString == "xitrum.annotation.Swagger.Param.apply$default$4")
              ""
            else
              children(4).productElement(0).asInstanceOf[universe.Constant].value.toString

          Swagger.Param(name, makeParamType(paramType), makeValueType(valueType), description)
        } else if (paramOrResponse.tpe == universe.typeOf[Swagger.OptionalParam]) {
          val children = paramOrResponse.children

          val name      = children(1).productElement(0).asInstanceOf[universe.Constant].value.toString
          val paramType = children(2).toString
          val valueType = children(3).toString

          val description =
            if (children(4).toString == "xitrum.annotation.Swagger.OptionalParam.apply$default$4")
              ""
            else
              children(4).productElement(0).asInstanceOf[universe.Constant].value.toString

          Swagger.OptionalParam(name, makeParamType(paramType), makeValueType(valueType), description)
        } else {  //if (paramOrResponse.tpe == universe.typeOf[Swagger.Response]) {
          // Ex: List(xitrum.annotation.Swagger.Response.apply, 200, "ID of the newly created article will be returned")
          val children = paramOrResponse.children

          val code    = children(1).toString.toInt
          val message = children(2).productElement(0).asInstanceOf[universe.Constant].value.toString

          Swagger.Response(code, message)
        }
      }

      Swagger(summary, swaggerVarargs: _*)
    }
  }

  private def makeParamType(paramType: String): Swagger.ParamType = {
    paramType match {
      case "xitrum.annotation.Swagger.Path"   => Swagger.Path
      case "xitrum.annotation.Swagger.Query"  => Swagger.Query
      case "xitrum.annotation.Swagger.Body"   => Swagger.Body
      case "xitrum.annotation.Swagger.Header" => Swagger.Header
      case "xitrum.annotation.Swagger.Form"   => Swagger.Form
    }
  }

  private def makeValueType(valueType: String): Swagger.ValueType = {
    valueType match {
      case "xitrum.annotation.Swagger.Byte"  => Swagger.Byte
      case "xitrum.annotation.Swagger.Int"   => Swagger.Int
      case "xitrum.annotation.Swagger.Int32" => Swagger.Int32
      case "xitrum.annotation.Swagger.Int64" => Swagger.Int64
      case "xitrum.annotation.Swagger.Long"  => Swagger.Long

      case "xitrum.annotation.Swagger.Number" => Swagger.Number
      case "xitrum.annotation.Swagger.Float"  => Swagger.Float
      case "xitrum.annotation.Swagger.Double" => Swagger.Double

      case "xitrum.annotation.Swagger.String"  => Swagger.String
      case "xitrum.annotation.Swagger.Boolean" => Swagger.Boolean

      case "xitrum.annotation.Swagger.Date"     => Swagger.Date
      case "xitrum.annotation.Swagger.DateTime" => Swagger.DateTime
    }
  }
}

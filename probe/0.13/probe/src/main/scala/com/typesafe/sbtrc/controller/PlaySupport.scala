package com.typesafe.sbtrc
package controller

import com.typesafe.sbt.ui.{ Context => UIContext, Params, SimpleJsonMessage }
import com.typesafe.sbt.ui
import java.lang.reflect.{ Method, Proxy }
import com.typesafe.sbt.ui.SimpleJsonMessage
import scala.util.parsing.json.JSONObject
import sbt._

object PlaySupport {

  // Our mechanism of blocking until the user cancels.
  @annotation.tailrec
  final def blockForCancel(ctx: UIContext): Unit = {
    ctx.take() match {
      case ui.NoUIPresent => ()
      case ui.Canceled => ()
      case ui.Request(name, handle, sendError) =>
        sendError("Request not supported during play run: " + name)
        blockForCancel(ctx)
    }
  }

  class PlayInteractionHandler(ui: UIContext) extends java.lang.reflect.InvocationHandler {
    def invoke(proxy: AnyRef, m: Method, args: Array[AnyRef]): AnyRef = {
      // Just hook after started...
      m.getName match {
        case "waitForCancel" =>
          blockForCancel(ui)
        case "doWithoutEcho" =>
          // Just Read function and run it.
          val func = args(0).asInstanceOf[Function0[Unit]]
          func()
        case "toString" =>
          return "PlayInteractionHandler(" + ui + ")"
        case name =>
        // We specifically ignore any other call.
      }
      ().asInstanceOf[AnyRef]
    }
  }
  // This is an ugly reflective hack to use our interaction rather than
  // play's.  We're in an alternative classloader though.
  def hackyAssignSetting[T](key: SettingKey[T], value: AnyRef): Setting[T] =
    key <<= Def.setting { value.asInstanceOf[T] }

  def makePlayInteractionSetting(setting: Setting[_], ui: UIContext): Setting[_] = {
    val key = setting.key
    val interactionClass = key.key.manifest.runtimeClass
    val proxy =
      Proxy.newProxyInstance(
        interactionClass.getClassLoader,
        Array(interactionClass),
        new PlayInteractionHandler(ui))
    // This is an ugly reflective hack to use our interaction rather than
    // play's.  We're in an alternative classloader though.
    hackyAssignSetting(SettingKey(key.key) in key.scope, proxy)
  }
  /**
   * This class represents a dynamic proxy we use to avoid classpath hell when
   *  working with the play plugin.
   */
  class PlayRunHookHandler(ui: UIContext) extends java.lang.reflect.InvocationHandler {
    def invoke(proxy: AnyRef, m: Method, args: Array[AnyRef]): AnyRef = {
      // Just hook after started...
      m.getName match {
        case "afterStarted" =>
          val socket = args(0).asInstanceOf[java.net.InetSocketAddress]
          val msg = SimpleJsonMessage(JSONObject(Map(
            "host" -> socket.getHostName,
            "port" -> socket.getPort)))
          ui.sendEvent("playServerStarted", msg)
          null
        case "toString" =>
          "PlayRunHookHandler(" + ui + ")"
        case name =>
          // We specifically ignore all other requests
          null
      }
    }
    // return something
  }

  def hackyAddToTask[T](key: TaskKey[Seq[T]], element: Any): Setting[Task[Seq[T]]] =
    key := {
      (element.asInstanceOf[T] +: key.value)
    }

  def makeDynamicProxyRunHookSetting(setting: Setting[_], ui: UIContext): Setting[_] = {
    val key = setting.key
    val mf = key.key.manifest
    // Manfiest is a Task[Seq[PlayRunHook]], so we want the first type argument of
    // the first type argument....
    val runHookClass = mf.typeArguments(0).typeArguments(0).runtimeClass
    val proxy =
      Proxy.newProxyInstance(
        runHookClass.getClassLoader,
        Array(runHookClass),
        new PlayRunHookHandler(ui))
    // This is a very sketchy reflective hack.
    hackyAddToTask(TaskKey(key.key.asInstanceOf[AttributeKey[Task[Seq[AnyRef]]]]) in key.scope, proxy)
  }

  // New proxy
  // java.lang.reflect.Proxy.newProxyInstance(obj.getClass().getClassLoader(),
  //                                        Class[] { MyProxyInterface.class },
  //                                        new MyDynamicProxyClass(obj));

  def findPlaySetting(name: String, settings: Seq[Setting[_]]): Option[Setting[_]] =
    (for {
      setting <- settings
      if setting.key.key.label == name
    } yield setting).headOption

  // Adds our hooks into the play build.
  def installHooks(state: State, ui: UIContext): State = {
    val extracted = Project.extract(state)
    val settings = extracted.session.mergeSettings
    val runHookKey = findPlaySetting("playRunHooks", settings).getOrElse(
      sys.error("Unable to find play run hook!  Possibly incompatible play version."))
    val fixedHook = makeDynamicProxyRunHookSetting(runHookKey, ui)
    val interactionKey = findPlaySetting("playInteractionMode", settings).getOrElse(
      sys.error("Unable to find play run hook!  Possibly incompatible play version."))
    val fixedInteraction = makePlayInteractionSetting(interactionKey, ui)
    val newSettings = Seq[Setting[_]](fixedHook, fixedInteraction)
    SbtUtil.reloadWithAppended(state, newSettings)
  }

  def installPlaySupport(origState: State, ui: UIContext): State = {
    if (isPlayProject(origState)) installHooks(origState, ui)
    else origState
  }
}
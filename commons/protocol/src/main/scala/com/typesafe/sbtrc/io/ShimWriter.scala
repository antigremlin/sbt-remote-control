package com.typesafe.sbtrc.io

import java.io.File
import sbt.IO

object FileHasher {

  def sha512(file: File): String = {
    val md = java.security.MessageDigest.getInstance("SHA-512")
    val in = new java.io.FileInputStream(file);
    val buffer = new Array[Byte](8192)
    try {
      def read(): Unit = in.read(buffer) match {
        case x if x <= 0 => ()
        case size => md.update(buffer, 0, size); read()
      }
      read()
    } finally in.close()
    // Now migrate to a string we can compare
    digestToHexString(md.digest)
  }

  def digestToHexString(bytes: Array[Byte]): String = {
    val buf = new StringBuffer
    // TODO - error handling necessary?
    def byteToHex(b: Int) = HEX_CHARS(b)
    for (i <- 0 until bytes.length) {
      val b = bytes(i)
      buf append byteToHex((b >>> 4) & 0x0F)
      buf append byteToHex(b & 0x0F)
    }
    buf.toString
  }
  private val HEX_CHARS = "0123456789abcdef".toCharArray
}

// TODO - We want to re-use this across sbt versions...
class ShimWriter(val name: String, version: String, sbtBinaryVersion: String = "0.12", isEmpty: Boolean = false) {

  private val cleanedVersion = sbtBinaryVersion.replaceAll("\\W+", "-")

  private val addBootResovlersString =
    if(sbtBinaryVersion == "0.12") ShimWriter.addBootResolversSetting
    else ""

  private val addSbtPluginString: String = """

// shim plugins are needed when plugins are not "UI aware"
// (we need an interface for the UI program rather than an interface
// for a person at a command line).
// In future plans, we want plugins to have a built-in ability to be
// remote-controlled by a UI and then we would drop the shims.
addSbtPlugin("com.typesafe.sbtrc" % "sbt-rc-""" + name + "-" + cleanedVersion + "\" % \"" + version + "\")\n"

  private val sbtContents: String = addBootResovlersString + (if(isEmpty) "" else addSbtPluginString)

  // TODO - We'd like to remove the activator name here, but we'd have to clean up the existing shims first...
  private val SHIM_FILE_NAME = "activator-" + name + "-shim.sbt"

  private lazy val pluginSbtFile = {
    val tmp = java.io.File.createTempFile(name, "sbt-shim")
    IO.write(tmp, sbtContents)
    tmp.deleteOnExit()
    tmp
  }

  private lazy val sbtFileSha = FileHasher.sha512(pluginSbtFile)

  private def makeTarget(basedir: File): File =
    new File(new File(basedir, "project"), SHIM_FILE_NAME)

  // update the shim file ONLY if it already exists. Returns true if it makes a change.
  def updateIfExists(basedir: File): Boolean = {
    val target = makeTarget(basedir)
    if (target.exists && FileHasher.sha512(target) != sbtFileSha) {
      IO.copyFile(pluginSbtFile, target)
      true
    } else {
      false
    }
  }

  // update the shim file EVEN IF it doesn't exist. Returns true if it makes a change.
  def ensureExists(basedir: File): Boolean = {
    val target = makeTarget(basedir)
    if (target.exists && FileHasher.sha512(target) == sbtFileSha) {
      false
    } else {
      IO.copyFile(pluginSbtFile, target)
      true
    }
  }
}

object ShimWriter {
  val alwaysIncludedShims = Set("eclipse", "idea", "defaults")

  def sbt12Shims(version: String): Seq[ShimWriter] = Seq(
    new ShimWriter("defaults", version, "0.12"),
    new ShimWriter("eclipse", version, "0.12", isEmpty = true),
    new ShimWriter("idea", version, "0.12", isEmpty = true),
    new ShimWriter("play", version, "0.12")
  )
  def sbt13Shims(version: String): Seq[ShimWriter] = Seq(
    new ShimWriter("defaults", version, "0.13", isEmpty = true),
    new ShimWriter("eclipse", version, "0.13", isEmpty = true),
    new ShimWriter("idea", version, "0.13", isEmpty = true),
    new ShimWriter("play", version, "0.13", isEmpty = true)
  )

  def knownShims(version: String, sbtVersion: String = "0.12"): Seq[ShimWriter] =
    sbtVersion match {
      case "0.12" => sbt12Shims(version)
      case "0.13" => sbt13Shims(version)
      case _ => sys.error("Unsupported sbt version: " + sbtVersion)
    }

  val addBootResolversSetting = """
// Note: This file is autogenerated by Builder.  Please do not modify!
// Full resolvers can be removed in sbt 0.13
fullResolvers <<= (fullResolvers, bootResolvers, appConfiguration) map {
  case (rs, Some(b), app) =>
    def getResolvers(app: xsbti.AppConfiguration): Option[Seq[xsbti.Repository]] =
      try Some(app.provider.scalaProvider.launcher.ivyRepositories.toSeq)
      catch { case _: NoSuchMethodError => None }
    def findLocalResolverNames(resolvers: Seq[xsbti.Repository]): Seq[String] =
      for {
        r <- resolvers
        if r.isInstanceOf[xsbti.IvyRepository]
        ivy = r.asInstanceOf[xsbti.IvyRepository]
        if ivy.url.getProtocol == "file"
      } yield ivy.id
    val newResolvers: Seq[Resolver] =
      getResolvers(app).map(findLocalResolverNames).getOrElse(Nil).flatMap(name => b.find(_.name == name))
    newResolvers ++ rs
  case (rs, _, _) => rs
}
"""
}

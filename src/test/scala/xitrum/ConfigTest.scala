package xitrum

import org.scalatest.Matchers
import org.scalatest.FlatSpec
import java.io.File

class ConfigTest extends FlatSpec with Matchers {
  behavior of "Config"

  it should "should load i18n settings" in {
	  val lookupFolders = Config.xitrum.i18n.map(_.lookupFolders).getOrElse(Seq.empty[File])
	  lookupFolders.map(_.toString) should equal (Seq("public/i18n"))
  }
  
}
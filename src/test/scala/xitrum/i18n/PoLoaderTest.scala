package xitrum.i18n

import java.io.File
import java.io.FileWriter

import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.Matchers

class PoLoaderTest extends FlatSpec with Matchers with BeforeAndAfterEach {

  val lookupFolder = new File(System.getProperty("java.io.tmpdir"))
  
  behavior of "PoLoader"

  override def beforeEach() {
    new File(lookupFolder, "en.po").delete()
    PoLoader.clear
  }
  
  it should "load po file from resource" in {
    PoLoader.get("en").t("test_message") should equal("I18N test message")
  }

  it should "load po file from addtional folders" in {
    val file = new File(lookupFolder, "en.po")
    write(file, "test_message_2", "I18N test message 2")
    file.deleteOnExit()
    
    val po = PoLoader.get("en", Seq(lookupFolder))
    po.t("test_message")   should equal("I18N test message")
    po.t("test_message_2") should equal("I18N test message 2")
  }
  
  it should "reload po file if it changed " in {
    val file = new File(lookupFolder, "en.po")
    write(file, "test_message_2", "I18N test message 2")
    file.deleteOnExit()
    
    val po1 = PoLoader.reload("en", Seq(lookupFolder))
    po1.t("test_message_2") should equal("I18N test message 2")
    po1.t("test_message_3") should equal("test_message_3")

    Thread.sleep(1000)
    write(file, "test_message_3", "I18N test message 3")
    
    val po2 = PoLoader.reload("en", Seq(lookupFolder))
    po2.t("test_message_2") should equal("I18N test message 2")
    po2.t("test_message_3") should equal("I18N test message 3")
  }

  private def write(file: File, msgid: String, msgstr: String) {
    val writer = new FileWriter(file, true)
    writer.write("msgid \"" + msgid + "\"\n")
    writer.write("msgstr \"" + msgstr + "\"\n")
    writer.close()
  }

}
package xitrum.i18n

import java.io.File
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.{Map => MMap}
import scaposer.Parser
import scaposer.Po
import xitrum.etag.ResourceKey
import xitrum.local.LruCache
import xitrum.util.Loader
import xitrum.Config
import org.jboss.netty.util.CharsetUtil.UTF_8

object PoLoader {
  private[this] val FAST_CACHE_TIME = if (Config.productionMode) 5 else 0

  private[this] val fastCache       = new LruCache()
  private[this] val longCache       = MMap.empty[String, (Set[ResourceKey], Po)]

  /**
   * @return Merge of all po files of the language, or an empty Po when there's
   * no po file.
   */
  def get(language: String, lookupFolder: Seq[File] = Seq.empty[File]): Po = synchronized {
    fastCache.getAs[Po](language).getOrElse({
      val po = reload(language, lookupFolder)
      fastCache.putMinute(language, po, FAST_CACHE_TIME)
      po
    })
  }
  
  /**
   * Remove already loaded files from PoLoader
   */
  def clear() = synchronized {
    fastCache.clear
    longCache.clear
  }

  private[i18n] def reload(language: String, lookupFolder: Seq[File]): Po = {
    val resources = {
      for (
        folder <- lookupFolder;
        file = new File(folder, language + ".po"); if file.exists
      ) yield ResourceKey(file)
    }.toSet
    
    val po = for (
      candidate <- longCache.get(language);
      if (resources.equals(candidate._1))
    ) yield candidate._2

    po.getOrElse({
      val po = load(language, lookupFolder)
      longCache.put(language, (resources, po))
      po
    })
  }

  private def load(language: String, lookupFolder: Seq[File]): Po = {
    val buffer = ListBuffer.empty[Po]

    loadFromResources(language, buffer)
    loadFromFileSystem(language, lookupFolder, buffer)

    buffer.foldLeft(new Po(Map.empty)) { (acc, e) => acc ++ e }
  }

  private def loadFromResources(language: String, buffer: ListBuffer[Po]) = {
    val urlEnum = getClass.getClassLoader.getResources("i18n/" + language + ".po")

    while (urlEnum.hasMoreElements) {
      val url    = urlEnum.nextElement
      val is     = url.openStream
      val bytes  = Loader.bytesFromInputStream(is)
      val string = new String(bytes, UTF_8)
      Parser.parsePo(string).foreach(buffer.append(_))
    }
  }

  private def loadFromFileSystem(language: String, lookupFolder: Seq[File], buffer: ListBuffer[Po]) = {
    val files =
      for (folder <- lookupFolder)
        yield new File(folder, language + ".po")

    for (file <- files; if file.exists) {
      val bytes  = Loader.bytesFromFile(file.getAbsoluteFile.toString)
      val string = new String(bytes, UTF_8)
      Parser.parsePo(string).foreach(buffer.append(_))
    }
  }
}

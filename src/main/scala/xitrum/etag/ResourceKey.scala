package xitrum.etag

import java.io.File

case class ResourceKey(resource: String, modifiedTime: Long)

object ResourceKey {

  def apply(file: File): ResourceKey = if (file.exists) { 
    ResourceKey(file.getAbsoluteFile.toString, file.lastModified)
  } else {
    ResourceKey(file.getAbsoluteFile.toString)
  }
  
  def apply(resource: String): ResourceKey = ResourceKey(resource, 0l) 
  
}
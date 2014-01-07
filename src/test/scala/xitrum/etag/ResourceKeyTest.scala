package xitrum.etag

import org.scalatest.Matchers
import org.scalatest.FlatSpec
import java.io.File
import java.io.FileOutputStream

class ResourceKeyTest extends FlatSpec with Matchers {
  
  behavior of "ResourceKey"

  it should "change when file modified" in {
    val file = File.createTempFile("resourceKey", "test")
    file.deleteOnExit()
    
    val atStartup  = ResourceKey(file)
    val atStartup2 = ResourceKey(file)
    
    assert(atStartup.equals(atStartup2), "Resource keys not same")
    
    val stream = new FileOutputStream(file)
    stream.write(1)
    stream.close()
    
    val atEnd = ResourceKey(file)
    
    assert(atStartup.equals(atEnd), "Resource keys are same")
  }
  
  it should "works normal if file does not exists" in {
    val file1 = ResourceKey(new File("my-file-that-not-exists"))
    val file2 = ResourceKey(new File("my-file-that-not-exists"))
    
    assert(file1.equals(file2), "Resource keys not same")
  }
  
  it should "return same keys for simple string resource" in {
    val resource1 = ResourceKey("my-resource")
    val resource2 = ResourceKey("my-resource")
    
    assert(resource1.equals(resource2), "Resource keys not same")
  }

}
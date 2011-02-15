package xitrum.vc.env.session

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream, Serializable}
import scala.collection.mutable.{HashMap => MHashMap}

class CookieSession extends Session {
  private var map = new MHashMap[String, Serializable]

  def deserialize(base64String: String) {
    map = SecureBase64.deserialize(base64String) match {
      case None        => new MHashMap[String, Serializable]
      case Some(value) => value.asInstanceOf[MHashMap[String, Serializable]]
    }
  }

  def serialize: String = SecureBase64.serialize(map)

  //----------------------------------------------------------------------------

  def update(key: String, value: Any) { map(key) = value.asInstanceOf[Serializable] }

  def get[T](key: String): T = map.get(key).asInstanceOf[T]

  def contains(key: String) = map.contains(key)

  def delete(key: String) { map.remove(key) }

  def reset { map.clear }
}

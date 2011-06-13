package net.fyrie.redis

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers

import akka.dispatch.Promise

class OperationsSpec extends Spec
    with ShouldMatchers
    with RedisTestServer {

  describe("keys") {
    it("should fetch keys") {
      r.set("anshin-1", "debasish")
      r.set("anshin-2", "maulindu")
      r.sync.keys("anshin*").size should equal(2)
    }

    it("should fetch keys with spaces") {
      r.set("anshin 1", "debasish")
      r.set("anshin 2", "maulindu")
      r.sync.keys("anshin*").size should equal(2)
    }
  }

  describe("randomkey") {
    it("should give") {
      r.set("anshin-1", "debasish")
      r.set("anshin-2", "maulindu")
      r.sync.randomkey().parse[String] getOrElse fail("No key returned") should startWith("anshin")
    }
  }

  describe("rename") {
    it("should give") {
      r.set("anshin-1", "debasish")
      r.set("anshin-2", "maulindu")
      r.rename("anshin-2", "anshin-2-new")
      val thrown = evaluating { r.sync.rename("anshin-2", "anshin-2-new") } should produce[Exception]
      thrown.getMessage should equal("ERR no such key")
    }
  }

  describe("renamenx") {
    it("should give") {
      r.set("anshin-1", "debasish")
      r.set("anshin-2", "maulindu")
      r.sync.renamenx("anshin-2", "anshin-2-new") should equal(true)
      r.sync.renamenx("anshin-1", "anshin-2-new") should equal(false)
    }
  }

  describe("dbsize") {
    it("should give") {
      r.set("anshin-1", "debasish")
      r.set("anshin-2", "maulindu")
      r.sync.dbsize() should equal(2)
    }
  }

  describe("exists") {
    it("should give") {
      r.set("anshin-1", "debasish")
      r.set("anshin-2", "maulindu")
      r.sync.exists("anshin-2") should equal(true)
      r.sync.exists("anshin-1") should equal(true)
      r.sync.exists("anshin-3") should equal(false)
    }
  }

  describe("del") {
    it("should give") {
      r.set("anshin-1", "debasish")
      r.set("anshin-2", "maulindu")
      r.sync.del(Set("anshin-2", "anshin-1")) should equal(2)
      r.sync.del(Set("anshin-2", "anshin-1")) should equal(0)
    }
  }

  describe("type") {
    it("should give") {
      r.set("anshin-1", "debasish")
      r.set("anshin-2", "maulindu")
      r.sync.typeof("anshin-2") should equal("string")
    }
  }

  describe("expire") {
    it("should give") {
      r.set("anshin-1", "debasish")
      r.set("anshin-2", "maulindu")
      r.sync.expire("anshin-2", 1000) should equal(true)
      r.sync.expire("anshin-3", 1000) should equal(false)
    }
  }

  describe("Multi exec commands") {
    it("should work with single commands") {
      val p = Promise[Unit]()
      r.multi{ rq =>
        p <-: rq.set("testkey1", "testvalue1")
      }
      p.get should be(())
    }
    it("should work with several commands") {
      val p = Promise[List[Option[String]]]()
      r.multi{ rq =>
        rq.set("testkey1", "testvalue1")
        rq.set("testkey2", "testvalue2")
        p <-: rq.mget(List("testkey1", "testkey2")).parse[String]
      }
      p.get should be(List(Some("testvalue1"), Some("testvalue2")))
    }
    it("should throw an error") {
      val p1, p2 = Promise[Option[String]]()
      r.multi{ rq =>
        rq.set("a", "abc")
        p1 <-: rq.lpop("a").parse[String]
        p2 <-: rq.get("a").parse[String]
      }
      evaluating { p1.get } should produce[RedisErrorException]
      p2.get should be(Some("abc"))
    }
    it("should handle invalid requests") {
      val p1, p2 = Promise[List[Option[String]]]()
      r.multi{ rq =>
        rq.set("testkey1", "testvalue1")
        rq.set("testkey2", "testvalue2")
        p1 <-: rq.mget(List[String]()).parse[String]
        p2 <-: rq.mget(List("testkey1", "testkey2")).parse[String]
      }
      evaluating { p1.get } should produce[RedisErrorException]
      p2.get should be(List(Some("testvalue1"), Some("testvalue2")))
    }
  }

  describe("sort") {
    it("should do a simple sort") {
      List(6, 3, 5, 47, 1, 1, 4, 9) foreach (r.lpush("sortlist", _))
      r.sync.sort("sortlist").parse[Int].flatten should be(List(1, 1, 3, 4, 5, 6, 9, 47))
    }
    it("should do a lexical sort") {
      List("lorem", "ipsum", "dolor", "sit", "amet") foreach (r.lpush("sortlist", _))
      List(3, 7) foreach (r.lpush("sortlist", _))
      r.sync.sort("sortlist", alpha = true).parse[String].flatten should be(List("3", "7", "amet", "dolor", "ipsum", "lorem", "sit"))
    }
    it("should return an empty list if key not found") {
      r.sync.sort("sortnotfound") should be(Nil)
    }
    it("should return multiple items") {
      val list = List(("item1", "data1", 1, 4),
                      ("item2", "data2", 2, 8),
                      ("item3", "data3", 3, 1),
                      ("item4", "data4", 4, 6),
                      ("item5", "data5", 5, 3))
      for ((key, data, num, rank) <- list) {
        r.quiet.sadd("items", key)
        r.quiet.set("data::"+key, data)
        r.quiet.set("num::"+key, num)
        r.quiet.set("rank::"+key, rank)
      }
      r.quiet.del(List("num::item1"))
      r.sync.sort("items",
                  get = Seq("#", "data::*", "num::*"),
                  by = Some("rank::*"),
                  limit = Limit(1,3)).parse[String] should be(List(Some("item5"), Some("data5"), Some("5"),
                                                                   Some("item1"), Some("data1"), None,
                                                                   Some("item4"), Some("data4"), Some("4")))
      r.sync.sort("items",
                  get = Seq("#", "data::*", "num::*"),
                  by = Some("rank::*"),
                  limit = Limit(1,3)).parse[String,String,Int] should be(List((Some("item5"), Some("data5"), Some(5)),
                                                                              (Some("item1"), Some("data1"), None),
                                                                              (Some("item4"), Some("data4"), Some(4))))
    }
  }
}

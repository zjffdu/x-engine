/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.spark;

import org.apache.zeppelin.display.AngularObjectRegistry;
import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.interpreter.InterpreterGroup;
import org.apache.zeppelin.interpreter.InterpreterOutput;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.interpreter.InterpreterResult.Type;
import org.apache.zeppelin.interpreter.remote.RemoteInterpreterEventClient;
import org.apache.zeppelin.resource.LocalResourcePool;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedList;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class SparkSqlInterpreterTest {

  private static SparkSqlInterpreter sqlInterpreter;
  private static SparkInterpreter sparkInterpreter;
  private static InterpreterContext context;
  private static InterpreterGroup intpGroup;

  @BeforeClass
  public static void setUp() throws Exception {
    Properties p = new Properties();
    p.setProperty("spark.master", "local[4]");
    p.setProperty("spark.app.name", "test");
    p.setProperty("zeppelin.spark.maxResult", "10");
    p.setProperty("zeppelin.spark.concurrentSQL", "true");
    p.setProperty("zeppelin.spark.sql.stacktrace", "true");
    p.setProperty("zeppelin.spark.useHiveContext", "true");
    p.setProperty("zeppelin.spark.deprecatedMsg.show", "false");

    intpGroup = new InterpreterGroup();
    sparkInterpreter = new SparkInterpreter(p);
    sparkInterpreter.setInterpreterGroup(intpGroup);

    sqlInterpreter = new SparkSqlInterpreter(p);
    sqlInterpreter.setInterpreterGroup(intpGroup);
    intpGroup.put("session_1", new LinkedList<Interpreter>());
    intpGroup.get("session_1").add(sparkInterpreter);
    intpGroup.get("session_1").add(sqlInterpreter);

    context = InterpreterContext.builder()
        .setNoteId("noteId")
        .setParagraphId("paragraphId")
        .setParagraphTitle("title")
        .setAngularObjectRegistry(new AngularObjectRegistry(intpGroup.getId(), null))
        .setResourcePool(new LocalResourcePool("id"))
        .setInterpreterOut(new InterpreterOutput(null))
        .setIntpEventClient(mock(RemoteInterpreterEventClient.class))
        .build();
    InterpreterContext.set(context);

    sparkInterpreter.open();
    sqlInterpreter.open();
  }

  @AfterClass
  public static void tearDown() throws InterpreterException {
    sqlInterpreter.close();
    sparkInterpreter.close();
  }

  @Test
  public void test() throws InterpreterException {
    sparkInterpreter.interpret("case class Test(name:String, age:Int)", context);
    sparkInterpreter.interpret("val test = sc.parallelize(Seq(Test(\"moon\", 33), Test(\"jobs\", 51), Test(\"gates\", 51), Test(\"park\", 34)))", context);
    sparkInterpreter.interpret("test.toDF.registerTempTable(\"test\")", context);

    InterpreterResult ret = sqlInterpreter.interpret("select name, age from test where age < 40", context);
    assertEquals(InterpreterResult.Code.SUCCESS, ret.code());
    assertEquals(Type.TABLE, ret.message().get(0).getType());
    assertEquals("name\tage\nmoon\t33\npark\t34\n", ret.message().get(0).getData());

    ret = sqlInterpreter.interpret("select wrong syntax", context);
    assertEquals(InterpreterResult.Code.ERROR, ret.code());
    assertTrue(ret.message().get(0).getData().length() > 0);

    assertEquals(InterpreterResult.Code.SUCCESS, sqlInterpreter.interpret("select case when name='aa' then name else name end from test", context).code());
  }

  @Test
  public void testStruct() throws InterpreterException {
    sparkInterpreter.interpret("case class Person(name:String, age:Int)", context);
    sparkInterpreter.interpret("case class People(group:String, person:Person)", context);
    sparkInterpreter.interpret(
        "val gr = sc.parallelize(Seq(People(\"g1\", Person(\"moon\",33)), People(\"g2\", Person(\"sun\",11))))",
        context);
    sparkInterpreter.interpret("gr.toDF.registerTempTable(\"gr\")", context);

    InterpreterResult ret = sqlInterpreter.interpret("select * from gr", context);
    assertEquals(InterpreterResult.Code.SUCCESS, ret.code());

  }

  public void test_null_value_in_row() throws InterpreterException {
    sparkInterpreter.interpret("import org.apache.spark.sql._", context);
    sparkInterpreter.interpret(
        "import org.apache.spark.sql.types.{StructType,StructField,StringType,IntegerType}",
        context);

    sparkInterpreter.interpret(
        "def toInt(s:String): Any = {try { s.trim().toInt} catch {case e:Exception => null}}",
        context);
    sparkInterpreter.interpret(
        "val schema = StructType(Seq(StructField(\"name\", StringType, false),StructField(\"age\" , IntegerType, true),StructField(\"other\" , StringType, false)))",
        context);
    sparkInterpreter.interpret(
        "val csv = sc.parallelize(Seq((\"jobs, 51, apple\"), (\"gates, , microsoft\")))",
        context);
    sparkInterpreter.interpret(
        "val raw = csv.map(_.split(\",\")).map(p => Row(p(0),toInt(p(1)),p(2)))",
        context);
    sparkInterpreter.interpret("val people = sqlContext.createDataFrame(raw, schema)",
        context);
    sparkInterpreter.interpret("people.toDF.registerTempTable(\"people\")", context);

    InterpreterResult ret = sqlInterpreter.interpret(
        "select name, age from people where name = 'gates'", context);
    assertEquals(InterpreterResult.Code.SUCCESS, ret.code());
    assertEquals(Type.TABLE, ret.message().get(0).getType());
    assertEquals("name\tage\ngates\tnull\n", ret.message().get(0).getData());
  }

  @Test
  public void testMaxResults() throws InterpreterException {
    sparkInterpreter.interpret("case class P(age:Int)", context);
    sparkInterpreter.interpret(
        "val gr = sc.parallelize(Seq(P(1),P(2),P(3),P(4),P(5),P(6),P(7),P(8),P(9),P(10),P(11)))",
        context);
    sparkInterpreter.interpret("gr.toDF.registerTempTable(\"gr\")", context);

    InterpreterResult ret = sqlInterpreter.interpret("select * from gr", context);
    assertEquals(InterpreterResult.Code.SUCCESS, ret.code());
    // the number of rows is 10+1, 1 is the head of table
    assertEquals(11, ret.message().get(0).getData().split("\n").length);
    assertTrue(ret.message().get(1).getData().contains("alert-warning"));

    // test limit local property
    context.getLocalProperties().put("limit", "5");
    ret = sqlInterpreter.interpret("select * from gr", context);
    assertEquals(InterpreterResult.Code.SUCCESS, ret.code());
    // the number of rows is 5+1, 1 is the head of table
    assertEquals(6, ret.message().get(0).getData().split("\n").length);
  }

  @Test
  public void testConcurrentSQL() throws InterpreterException, InterruptedException {
    if (sparkInterpreter.getSparkVersion().isSpark2()) {
      sparkInterpreter.interpret("spark.udf.register(\"sleep\", (e:Int) => {Thread.sleep(e*1000); e})", context);
    } else {
      sparkInterpreter.interpret("sqlContext.udf.register(\"sleep\", (e:Int) => {Thread.sleep(e*1000); e})", context);
    }

    Thread thread1 = new Thread() {
      @Override
      public void run() {
        try {
          InterpreterResult result = sqlInterpreter.interpret("select sleep(10)", context);
          assertEquals(InterpreterResult.Code.SUCCESS, result.code());
        } catch (InterpreterException e) {
          e.printStackTrace();
        }
      }
    };

    Thread thread2 = new Thread() {
      @Override
      public void run() {
        try {
          InterpreterResult result = sqlInterpreter.interpret("select sleep(10)", context);
          assertEquals(InterpreterResult.Code.SUCCESS, result.code());
        } catch (InterpreterException e) {
          e.printStackTrace();
        }
      }
    };

    // start running 2 spark sql, each would sleep 10 seconds, the totally running time should
    // be less than 20 seconds, which means they run concurrently.
    long start = System.currentTimeMillis();
    thread1.start();
    thread2.start();
    thread1.join();
    thread2.join();
    long end = System.currentTimeMillis();
    assertTrue("running time must be less than 20 seconds", ((end - start)/1000) < 20);

  }

  @Test
  public void testDDL() throws InterpreterException {
    InterpreterResult ret = sqlInterpreter.interpret("create table t1(id int, name string)", context);
    assertEquals(InterpreterResult.Code.SUCCESS, ret.code());
    // spark 1.x will still return DataFrame with non-empty columns.
    // org.apache.spark.sql.DataFrame = [result: string]
    if (!sparkInterpreter.getSparkContext().version().startsWith("1.")) {
      assertTrue(ret.message().isEmpty());
    } else {
      assertEquals(Type.TABLE, ret.message().get(0).getType());
      assertEquals("result\n", ret.message().get(0).getData());
    }

    // create the same table again
    ret = sqlInterpreter.interpret("create table t1(id int, name string)", context);
    assertEquals(InterpreterResult.Code.ERROR, ret.code());
    assertEquals(1, ret.message().size());
    assertEquals(Type.TEXT, ret.message().get(0).getType());
    assertTrue(ret.message().get(0).getData().contains("already exists"));

    // invalid DDL
    ret = sqlInterpreter.interpret("create temporary function udf1 as 'org.apache.zeppelin.UDF'", context);
    assertEquals(InterpreterResult.Code.ERROR, ret.code());
    assertEquals(1, ret.message().size());
    assertEquals(Type.TEXT, ret.message().get(0).getType());

    // spark 1.x could not detect the root cause correctly
    if (!sparkInterpreter.getSparkContext().version().startsWith("1.")) {
      assertTrue(ret.message().get(0).getData().contains("ClassNotFoundException") ||
              ret.message().get(0).getData().contains("Can not load class"));
    }
  }
}

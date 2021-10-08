package zio.examples

import zio._

case class Person(name: String, age: Int)

object Person {
  val peter: Person = Person("Peter", 88)
}

object succeedNow extends ZIOApp {
  val peterZIO =
    ZIO.succeedNow(Person.peter)

  def run = peterZIO
}

object succeedNowUhOh extends ZIOApp {
  val howdyZIO =
    ZIO.succeedNow(println("Howdy! 🐴🤠"))

  def run = ZIO.succeedNow(1)
}

object succeed extends ZIOApp {
  val howdyZIO =
    ZIO.succeed(println("Howdy! 🐴🤠"))

  def run = howdyZIO
}

object succeedAgain extends ZIOApp {
  def printLine(message: String) =
    ZIO.succeed(println(message))

  def run = printLine("Fancy 🤩")
}

object zip extends ZIOApp {
  val zippedZIO =
    ZIO.succeed(8) zip ZIO.succeed("LO") zip ZIO.succeed(13)

  def run = zippedZIO
}

object map extends ZIOApp {
  val zippedZIO: ZIO[Any, Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  val personZIO: ZIO[Any, Nothing, Person] =
    zippedZIO.map { case (int, string) =>
      Person(string, int)
    }

  val mappedZIO: ZIO[Any, Nothing, String] =
    zippedZIO.map { case (int, string) =>
      string * int
    }

  def run = personZIO
}

object mapUhOh extends ZIOApp {
  val zippedZIO: ZIO[Any, Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.succeed(println(message))

  val mappedZIO: ZIO[Any, Nothing, ZIO[Any, Nothing, Unit]] =
    zippedZIO.map { tuple =>
      printLine(s"MY BEAUTIFUL TUPLE: $tuple")
    }

  def run = mappedZIO
}

object flatMap extends ZIOApp {
  val zippedZIO: ZIO[Any, Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.succeed(println(message))

  val flatMappedZIO =
    zippedZIO.flatMap { tuple =>
      printLine(s"MY BEAUTIFUL TUPLE: $tuple")
    }

  def run = flatMappedZIO
}

object forComprehension extends ZIOApp {
  val zippedZIO: ZIO[Any, Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.succeed(println(message))

  val flatMappedZIO =
    zippedZIO
      .flatMap(tuple =>
        printLine(s"MY BEAUTIFUL TUPLE: $tuple")
          .as("Nice")
      )

  println(flatMappedZIO)

  def run = flatMappedZIO
}

object async extends ZIOApp {
  val asyncZIO: ZIO[Any, Nothing, Int] =
    ZIO.async[Int] { complete =>
      println("ASYNC BEGINNETH!")
      Thread.sleep(2000)
      complete(10)
    }

  def run = asyncZIO
}

object fork extends ZIOApp {
  val asyncZIO = ZIO.async[Int] { complete =>
    println("ASYNC BEGINNETH!")
    Thread.sleep(2000)
    complete(scala.util.Random.nextInt(999))
  }

  def printLine(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.succeed(println(message))

  //       + ------------------------------> Int
  // ZIO[Int].fork --> printLine  ---------> Int
  val forkedZIO = for {
    fiber  <- asyncZIO.fork
    fiber2 <- asyncZIO.fork
    _      <- printLine("NICE")
    int    <- fiber.join
    int2   <- fiber2.join
  } yield s"MY BEAUTIFUL INTs ($int, $int2)"

  def run = forkedZIO
}
//
object zipPar extends ZIOApp {
  val asyncZIO = ZIO.async[Int] { complete =>
    println("ASYNC BEGINNETH!")
    Thread.sleep(1000)
    complete(scala.util.Random.nextInt(999))
  }

  def run = asyncZIO zipPar asyncZIO
}

object StackSafety extends ZIOApp {

  val myProgram =
    ZIO.succeed(println("Howdy!")).repeat(100000)

  def run = myProgram
}

object ErrorHandling extends ZIOApp {

  val myProgram =
    ZIO
      .fail("Failed!")
      .flatMap(_ => ZIO.succeed(println("Here")))
      .catchAll(e => ZIO.succeed(println("Recovered from an error")))

  def run = myProgram
}

object ErrorHandling2 extends ZIOApp {

  val io: ZIO[Any, Nothing, Int] =
    ZIO
      .succeed { throw new NoSuchElementException("No such element") }
      .catchAll(_ => ZIO.succeed(println("This should never be shown")))
      .foldCauseZIO(
        c => ZIO.succeed(println(s"Recovered from a cause $c")) *> ZIO.succeed(1),
        _ => ZIO.succeed(0)
      ).map(_ + 10)

  def run = io
}

object Interruption extends ZIOApp {

  val io = for {
    fiber <- (ZIO.succeed(println("Howdy!")).repeat(10000).uninterruptible *>
                ZIO.succeed(println("Howdy Howdy!")).forever)
      .ensuring(ZIO.succeed(println("Bowdy!"))).fork
    _     <- ZIO.succeed(Thread.sleep(500))
    _     <- fiber.interrupt
  } yield ()

  def run = io
}
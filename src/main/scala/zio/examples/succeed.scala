package zio.examples

import zio._

case class Person(name: String, age: Int)

object Person {
  val peter: Person = Person("Peter", 88)
}

trait ZIOApp {
  def run: ZIO[Any]

  def main(args: Array[String]): Unit =
    run.run { result =>
      println(s"THE RESULT WAS $result")
    }
}

object succeedNow extends ZIOApp {
  val peterZIO: ZIO[Person] =
    ZIO.succeedNow(Person.peter)

  def run = peterZIO
}

object succeedNowUhOh extends ZIOApp {
  val howdyZIO: ZIO[Unit] =
    ZIO.succeedNow(println("Howdy! 🐴🤠"))

  def run = ZIO.succeedNow(1)
}

object succeed extends ZIOApp {
  val howdyZIO =
    ZIO.succeed(println("Howdy! 🐴🤠"))

  def run = howdyZIO
}

object succeedAgain extends ZIOApp {
  def printLine(message: String): ZIO[Unit] =
    ZIO.succeed(println(message))

  def run = printLine("Fancy 🤩")
}

object zip extends ZIOApp {
  val zippedZIO =
    ZIO.succeed(8) zip ZIO.succeed("LO") zip ZIO.succeed(13)

  def run = zippedZIO
}

object map extends ZIOApp {
  val zippedZIO: ZIO[(Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  val personZIO: ZIO[Person] =
    zippedZIO.map { case (int, string) =>
      Person(string, int)
    }

  val mappedZIO: ZIO[String] =
    zippedZIO.map { case (int, string) =>
      string * int
    }

  def run = personZIO
}

object mapUhOh extends ZIOApp {
  val zippedZIO: ZIO[(Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Unit] =
    ZIO.succeed(println(message))

  val mappedZIO: ZIO[ZIO[Unit]] =
    zippedZIO.map { tuple =>
      printLine(s"MY BEAUTIFUL TUPLE: $tuple")
    }

  def run = mappedZIO
}

object flatMap extends ZIOApp {
  val zippedZIO: ZIO[(Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Unit] =
    ZIO.succeed(println(message))

  val flatMappedZIO =
    zippedZIO.flatMap { tuple =>
      printLine(s"MY BEAUTIFUL TUPLE: $tuple")
    }

  def run = flatMappedZIO
}

object forComprehension extends ZIOApp {
  val zippedZIO: ZIO[(Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Unit] =
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
  val asyncZIO: ZIO[Int] =
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

  def printLine(message: String): ZIO[Unit] =
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

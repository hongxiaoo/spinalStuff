package andreasWallner.spinaltap

import spinal.core._
import spinal.sim._
import spinal.core.sim._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite
import spinal.lib.bus.amba3.apb.sim.Apb3Driver
import andreasWallner.io.iso7816.sim.ISO7816SimClient

import spinal.lib.bus.amba3.apb.Apb3
import andreasWallner.io.iso7816.{Apb3ISO7816Peripheral, ISO7816}
import spinal.lib._

object simLog {
  def apply(s: String) = {
    println(f"[${Console.BLUE}${simTime()}${Console.RESET}] $s")
  }
}

class SpinalTapTest extends AnyFunSuite {
  val dut = SimConfig.withWave.compile(new Component() {
    val spinaltap = new ApbSpinalTap()
    val io = new Bundle {
      val bus = slave(Apb3(32, 32))
      val iso = master(ISO7816())
    }
    spinaltap.io.bus <> io.bus
    io.iso.io <> spinaltap.io.port0(0)
    io.iso.rst <> spinaltap.io.port0.write(1)
    io.iso.clk <> spinaltap.io.port0.write(2)
    io.iso.vcc <> True

    for (i <- 1 to 4)
      spinaltap.io.port0.read(i) := spinaltap.io.port0.write(i)
    for (i <- 0 to 4)
      spinaltap.io.port1.read(i) := False
  })

  test("configure and run ISO communication") {
    dut.doSim("configure and run ISO communication") { dut =>
      SimTimeout(1000000)
      val driver = Apb3Driver(dut.io.bus, dut.clockDomain)
      ISO7816SimClient(dut.io.iso) { client =>
        val baudrate = 50
        val clockrate = 10e-6
        val T = 250 // 500

        dut.clockDomain.waitActiveEdge(10)
        simLog("waiting for activation")
        client.waitForActivation()
        sleep(100 * T)
        simLog("sending ATR")
        client.send("3B00", T)

        simLog("receiving")
        assert(client.receive(4, T) == List(0x11, 0x22, 0x33, 0x44))
        sleep(10 * T)
        simLog("responding")
        client.send("9021", T)
      }

      dut.clockDomain.forkStimulus(10)

      val isobase = 0x43c00400;
      val muxbase = 0x43c00300;

      driver.write(muxbase, 0x403)

      driver.write(isobase + 0x14, 25) // divider
      driver.write(isobase + 0x4c, 25)
      driver.write(isobase + 0x44, 0) //40000 * 50) // bwt
      driver.write(isobase + 0x48, 3000) //40000 * 50) // cwt

      driver.write(isobase + 0x18, 50 * 100) // ta
      driver.write(isobase + 0x1c, 50 * 500) // tb
      driver.write(isobase + 0x20, 1000) // te
      driver.write(isobase + 0x24, 1000) // th
      driver.write(isobase + 0x28, 1000) // vcc_offset
      driver.write(isobase + 0x2c, 1000) // clk_offset

      driver.write(isobase + 0x10, 0x11) // trigger activation & rx

      while (driver.read(isobase + 0x08) != 0) {}
      assert((driver.read(isobase + 0x30) & 0xffff) == 2)
      assert((driver.read(isobase + 0x3c) & 0xff) == 0x3b)
      assert((driver.read(isobase + 0x3c) & 0xff) == 0x00)
      assert((driver.read(isobase + 0x30) & 0xffff) == 0)
      dut.clockDomain.waitActiveEdge(10)

      simLog("sending request")
      driver.write(isobase + 0x40, 0x11)
      driver.write(isobase + 0x40, 0x22)
      driver.write(isobase + 0x40, 0x33)
      driver.write(isobase + 0x40, 0x44)
      driver.write(isobase + 0x10, 0x03) // trigger tx & rx

      simLog("receiving response")
      while (driver.read(isobase + 0x08) != 0) {}
      assert((driver.read(isobase + 0x30) & 0xffff) == 2)
      assert((driver.read(isobase + 0x3c) & 0xff) == 0x90)
      assert((driver.read(isobase + 0x3c) & 0xff) == 0x21)
      assert((driver.read(isobase + 0x30) & 0xffff) == 0)
      dut.clockDomain.waitActiveEdge(10)
    }
  }
}

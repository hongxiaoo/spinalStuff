package andreasWallner.io.pwm

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.misc.BusSlaveFactory
import andreasWallner.registers.BusSlaveFactoryRecorder
import andreasWallner.registers.datamodel.BusComponent
import andreasWallner.registers.casemodel.Value

import scala.language.postfixOps

object Pwm {
  case class CoreParameters(
      counterWidth: Int = 32,
      channelCnt: Int = 1
  )

  case class PeripheralParameters(
      core: CoreParameters = CoreParameters(),
      dividerWidth: Int = 32
  )

  class Ctrl[T <: spinal.core.Data with IMasterSlave](
      p: Pwm.PeripheralParameters,
      busType: HardType[T],
      metaFactory: T => BusSlaveFactory
  ) extends Component with BusComponent {
    val io = new Bundle {
      val bus = slave(busType())
      val pwm = out Vec (Bool(), p.core.channelCnt)
    }

    val factory = new BusSlaveFactoryRecorder(metaFactory(io.bus))

    // TODO ctrl register with run/disable

    val ctrl = factory.register(0x0, "ctrl")
    val run = ctrl.createReadAndWrite(
      Bool,
      0,
      "run",
      "Enable or disable counter and module output",
      List(
        Value(0, "dis", "Module disabled"),
        Value(1, "en", "Module enabled")
    )) init False

    val config0 = factory.register(0x04, "config0")
    val divider = new Area {
      val set = config0.createReadAndWrite(
        UInt(p.dividerWidth bits),
        0,
        "divider",
        "Divider configuring the base clock speed of the module"
      ) init 0
      val latched_set = Reg(UInt(p.dividerWidth bits)) init 0

      val cnt = Reg(UInt(p.dividerWidth bits))
      val enable = cnt === latched_set

      when(enable || !run) {
        latched_set := set
        cnt := 0
      } otherwise {
        cnt := cnt + 1
      }
    }

    val pre = new ClockEnableArea(divider.enable) {
      val core = Pwm.Core(p.core)
    }

    io.pwm := pre.core.io.pwm
    pre.core.io.run := run

    val max_count = factory.register(0x08, "config1").createReadAndWrite(
      UInt(p.core.counterWidth bits),
      0,
      "max_count",
      "Maximum counter value before roll-over, pwm period = f / divider / max_count"
    ) init 0
    val levels =
      for (i <- 0 until p.core.channelCnt)
        yield factory.register(0x0c + 4 * i, s"level${i}").createReadAndWrite(
          UInt(p.core.counterWidth bits),
          0,
          "val"
        ) init 0

    val updateValues = (pre.core.io.willOverflow && divider.enable) || !run
    pre.core.io.max_count := RegNextWhen(
      max_count,
      updateValues
    ) init 0
    for (i <- 0 until p.core.channelCnt)
      pre.core.io
        .levels(i) := RegNextWhen(
        levels(i),
        updateValues
      ) init 0

    override def elements = factory.elements
    override def busComponentName = "PWM"
    override def dataWidth = factory.dataWidth
    override def wordAddressInc = factory.wordAddressInc
  }

  case class Core(parameters: Pwm.CoreParameters) extends Component {
    val io = new Bundle {
      val max_count = in UInt (parameters.counterWidth bits)
      val levels = in Vec (UInt(parameters.counterWidth bits), parameters.channelCnt)
      val pwm = out Vec (Bool, parameters.channelCnt)
      val run = in Bool()
      val willOverflow = out Bool()
    }

    val counter = new Area {
      val value = Reg(UInt(parameters.counterWidth bits))
      io.willOverflow := False
      when(value === io.max_count || !io.run) {
        value := 0
        io.willOverflow := True
      } otherwise {
        value := value + 1
      }
    }

    for ((pwm, level) <- io.pwm.zip(io.levels))
      pwm := (counter.value < level) && io.run
  }
}

case class Apb3Pwm(
    parameter: Pwm.PeripheralParameters = Pwm.PeripheralParameters(),
    busConfig: Apb3Config = Apb3Config(12, 32)
) extends Pwm.Ctrl[Apb3](
      parameter,
      Apb3(busConfig),
      Apb3SlaveFactory(_)
    ) {}

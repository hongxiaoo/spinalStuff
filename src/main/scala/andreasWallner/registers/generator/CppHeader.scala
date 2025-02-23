package andreasWallner.registers.generator

import andreasWallner.registers.datamodel._
import spinal.core.{GlobalData, widthOf}
import java.io.{Writer, PrintWriter, File}

class CppHeader(
    component: BusComponent,
    namespace: Option[String] = None,
    offset: Long = 0
) {
  private var name = component.busComponentName
  def overrideName(newName: String) = {
    name = newName
    this
  }
  def addressType = s"uint32_t"
  def registerType = s"uint${component.dataWidth}_t"

  def write(): Unit =
    write(f"${GlobalData.get.phaseContext.config.targetDirectory}/${name}.hpp")
  def write(filename: String): Unit = {
    val writer = new PrintWriter(new File(filename))
    try {
      write(writer)
    } finally {
      writer.close();
    }
  }

  def write(writer: Writer): Unit = {
    writer.write(s"""#ifndef header_cpp_registers_${name}_h
      |#define header_cpp_registers_${name}_h
      |
      |#include <cstdint.h>
      |
      |""".stripMargin)
    if (namespace.nonEmpty)
      writer.write(s"namespace ${namespace.get} {\n")

    component.elements.map(writeElement(_, writer))

    if (namespace.nonEmpty)
      writer.write(s"}\n")
    writer.write(s"""#endif
      |""".stripMargin)
  }

  def writeElement(element: Element, writer: Writer): Unit = {
    element match {
      case c: Cluster  => c.elements.map(writeElement(_, writer))
      case r: Register => writeRegister(r, writer)
    }
  }

  def writeRegister(register: Register, writer: Writer): Unit = {
    writer.write(
      f"constexpr ${addressType} ${register.name} = 0x${register.address + offset}%xU;\n"
    )
    for (field <- register.fields) {
      writer.write(f"""constexpr unsigned int ${register.name}_${field.name}_pos = ${field.section.min}U;
        |constexpr ${registerType} ${register.name}_${field.name}_msk = 0x${field.section.bitmask}%xU;
        |""".stripMargin)
      for (value <- field.values) {
        assert(
          value.name != "pos" && value.name != "msk",
          "'pos' and 'msk' cant be used as value names"
        )
        writer.write(
          f"constexpr ${registerType} ${register.name}_${field.name}_${value.name} = (0x${value.value}%xU << ${field.section.min});\n"
        )
      }
    }
    writer.write("\n")
  }
}

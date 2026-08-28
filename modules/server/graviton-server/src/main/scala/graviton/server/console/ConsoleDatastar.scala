package graviton.server.console

import zio.blocks.html.{Dom, Js, div, toDomModifier}
import zio.http.datastar.dataOn

/**
 * Typed Datastar attributes rendered by zio-blocks-datastar.
 *
 * The console keeps its surrounding HTML server-rendered, while action
 * attributes are built through the ZIO Blocks algebra instead of handwritten
 * strings. Datastar accepts the resulting text/html responses and morphs the
 * element whose id is present in the fragment.
 */
private[console] object ConsoleDatastar:
  def click(expression: String): String =
    renderAttribute(dataOn.click := Js(expression))

  def clickPrevent(expression: String): String =
    renderAttribute(dataOn.click.prevent := Js(expression))

  def submit(expression: String): String =
    renderAttribute(dataOn.submit.prevent := Js(expression))

  private def renderAttribute(attribute: Dom.Attribute): String =
    val wrapper = div(attribute).render
    wrapper.substring("<div ".length, wrapper.length - "></div>".length)

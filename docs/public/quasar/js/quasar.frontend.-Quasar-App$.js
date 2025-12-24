'use strict';
import * as $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6 from "./internal-3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.js";
import * as $j_quasar$002efrontend$002e$002dBrowser$002dHttp$002dClient from "./quasar.frontend.-Browser-Http-Client.js";
import * as $j_quasar$002efrontend$002e$002dQuasar$002dApi from "./quasar.frontend.-Quasar-Api.js";
import * as $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage from "./quasar.frontend.-Quasar-App$-Page.js";
import * as $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dHome$0024 from "./quasar.frontend.-Quasar-App$-Page$-Home$.js";
import * as $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dLegacy$002dImport$0024 from "./quasar.frontend.-Quasar-App$-Page$-Legacy-Import$.js";
import * as $j_quasar$002efrontend$002ecomponents$002e$002dHealth$002dCheck$0024 from "./quasar.frontend.components.-Health-Check$.js";
import * as $j_quasar$002efrontend$002ecomponents$002e$002dLegacy$002dImport$0024 from "./quasar.frontend.components.-Legacy-Import$.js";
function $p_Lquasar_frontend_QuasarApp$__pageHref__Lquasar_frontend_QuasarApp$Page__T($thiz, page) {
  var x = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dHome$0024.$m_Lquasar_frontend_QuasarApp$Page$Home$();
  if ((x === page)) {
    return "#/";
  }
  var x$3 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dLegacy$002dImport$0024.$m_Lquasar_frontend_QuasarApp$Page$LegacyImport$();
  if ((x$3 === page)) {
    return "#/legacy-import";
  }
  throw new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_MatchError(page);
}
export { $p_Lquasar_frontend_QuasarApp$__pageHref__Lquasar_frontend_QuasarApp$Page__T as $p_Lquasar_frontend_QuasarApp$__pageHref__Lquasar_frontend_QuasarApp$Page__T };
function $p_Lquasar_frontend_QuasarApp$__navLink__Lquasar_frontend_QuasarApp$Page__T__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($thiz, page, label) {
  var $x_8 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).a__Lcom_raquo_laminar_tags_HtmlTag());
  var $x_7 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_6 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("nav-link");
  var $x_5 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls);
  var this$2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($thiz.Lquasar_frontend_QuasarApp$__f_router).Lcom_raquo_waypoint_Router__f_currentPageSignal);
  var project = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((current) => {
    var current$1 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage.$as_Lquasar_frontend_QuasarApp$Page(current);
    if ((current$1 === null)) {
      var $x_4 = (page === null);
    } else {
      var this$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(current$1);
      var $x_4 = (this$1 === page);
    }
    if ($x_4) {
      return "active";
    } else {
      return "";
    }
  }));
  var $x_3 = $x_5.$less$minus$minus__Lcom_raquo_airstream_core_Source__Lcom_raquo_laminar_keys_CompositeKey$CompositeValueMapper__Lcom_raquo_laminar_modifiers_KeyUpdater($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$ct_Lcom_raquo_airstream_misc_MapSignal__Lcom_raquo_airstream_core_Signal__F1__s_Option__(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_airstream_misc_MapSignal(), this$2, project, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_s_None$()), $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).StringValueMapper__Lcom_raquo_laminar_keys_CompositeKey$CompositeValueMappers$StringValueMapper$());
  var $x_2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).href__Lcom_raquo_laminar_keys_HtmlAttr()).$colon$eq__O__Lcom_raquo_laminar_modifiers_KeySetter($p_Lquasar_frontend_QuasarApp$__pageHref__Lquasar_frontend_QuasarApp$Page__T($thiz, page));
  var this$3 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var r = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
  var $x_1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$3, label, r);
  $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var eventProp = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).onClick__Lcom_raquo_laminar_keys_EventProp();
  var this$5 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_keys_EventProcessor$().empty__Lcom_raquo_laminar_keys_EventProp__Z__Z__Lcom_raquo_laminar_keys_EventProcessor(eventProp, false, false));
  var onNext = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((event) => {
    event.preventDefault();
    event.stopPropagation();
    $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($m_Lquasar_frontend_QuasarApp$().Lquasar_frontend_QuasarApp$__f_router).pushState__O__V(page);
  }));
  return $x_8.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_7.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_6, $x_3, $x_2, $x_1, new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_laminar_modifiers_EventListener(this$5, onNext)])));
}
export { $p_Lquasar_frontend_QuasarApp$__navLink__Lquasar_frontend_QuasarApp$Page__T__Lcom_raquo_laminar_nodes_ReactiveHtmlElement as $p_Lquasar_frontend_QuasarApp$__navLink__Lquasar_frontend_QuasarApp$Page__T__Lcom_raquo_laminar_nodes_ReactiveHtmlElement };
function $p_Lquasar_frontend_QuasarApp$__renderPage__Lquasar_frontend_QuasarApp$Page__Lquasar_frontend_QuasarApi__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($thiz, page, api) {
  var x = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dHome$0024.$m_Lquasar_frontend_QuasarApp$Page$Home$();
  if ((x === page)) {
    var $x_21 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).div__Lcom_raquo_laminar_tags_HtmlTag());
    var $x_20 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
    var $x_19 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).h2__Lcom_raquo_laminar_tags_HtmlTag());
    var $x_18 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
    var this$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
    var r = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
    var $x_17 = $x_19.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_18.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$1, "Quasar UI", r)])));
    var $x_16 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).p__Lcom_raquo_laminar_tags_HtmlTag());
    var $x_15 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
    var this$2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
    var r$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
    var $x_14 = $x_16.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_15.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$2, "This is a minimal Laminar shell intended to grow into the Quasar tenant-implicit UI.", r$1)])));
    var $x_13 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).ul__Lcom_raquo_laminar_tags_HtmlTag();
    var $x_12 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
    var $x_11 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).li__Lcom_raquo_laminar_tags_HtmlTag());
    var $x_10 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
    var this$3 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
    var r$2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
    var $x_9 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$3, "Health check via ", r$2);
    var $x_8 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).code__Lcom_raquo_laminar_tags_HtmlTag();
    var $x_7 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
    var this$4 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
    var r$3 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
    var $x_6 = $x_11.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_10.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_9, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($x_8).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_7.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$4, "GET /v1/health", r$3)])))])));
    var $x_5 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).li__Lcom_raquo_laminar_tags_HtmlTag();
    var $x_4 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
    var this$5 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
    var r$4 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
    var $x_3 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$5, "Legacy import via ", r$4);
    var $x_2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).code__Lcom_raquo_laminar_tags_HtmlTag();
    var $x_1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
    var this$6 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
    var r$5 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
    return $x_21.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_20.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_17, $x_14, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($x_13).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_12.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_6, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($x_5).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_4.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_3, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($x_2).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_1.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$6, "POST /v1/legacy/import", r$5)])))])))])))])));
  }
  var x$3 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dLegacy$002dImport$0024.$m_Lquasar_frontend_QuasarApp$Page$LegacyImport$();
  if ((x$3 === page)) {
    return $j_quasar$002efrontend$002ecomponents$002e$002dLegacy$002dImport$0024.$m_Lquasar_frontend_components_LegacyImport$().apply__Lquasar_frontend_QuasarApi__Lcom_raquo_laminar_nodes_ReactiveHtmlElement(api);
  }
  throw new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_MatchError(page);
}
export { $p_Lquasar_frontend_QuasarApp$__renderPage__Lquasar_frontend_QuasarApp$Page__Lquasar_frontend_QuasarApi__Lcom_raquo_laminar_nodes_ReactiveHtmlElement as $p_Lquasar_frontend_QuasarApp$__renderPage__Lquasar_frontend_QuasarApp$Page__Lquasar_frontend_QuasarApi__Lcom_raquo_laminar_nodes_ReactiveHtmlElement };
function $p_Lquasar_frontend_QuasarApp$__docHref$1__T__T__T($thiz, docsBaseNormalized$1, path) {
  var this$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(path);
  if ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$uZ(this$1.startsWith("/"))) {
    var normalizedPath = path;
  } else {
    var normalizedPath = ("/" + path);
  }
  return (("" + docsBaseNormalized$1) + normalizedPath);
}
export { $p_Lquasar_frontend_QuasarApp$__docHref$1__T__T__T as $p_Lquasar_frontend_QuasarApp$__docHref$1__T__T__T };
/** @constructor */
function $c_Lquasar_frontend_QuasarApp$() {
  this.Lquasar_frontend_QuasarApp$__f_homeRoute = null;
  this.Lquasar_frontend_QuasarApp$__f_legacyImportRoute = null;
  this.Lquasar_frontend_QuasarApp$__f_router = null;
  $n_Lquasar_frontend_QuasarApp$ = this;
  var $x_2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_Route$();
  var $x_1 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dHome$0024.$m_Lquasar_frontend_QuasarApp$Page$Home$();
  var this$2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_package$().Lcom_raquo_waypoint_Waypoint__f_root);
  var that = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_package$().endOfSegments__Lurldsl_language_PathSegment();
  var c = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lapp_tulz_tuplez_Composition$$anon$67();
  this.Lquasar_frontend_QuasarApp$__f_homeRoute = $x_2.static__O__Lurldsl_language_PathSegment__T__Lcom_raquo_waypoint_Route($x_1, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lurldsl_language_PathSegment__$div__Lurldsl_language_PathSegment__Lapp_tulz_tuplez_Composition__Lurldsl_language_PathSegment(this$2, that, c), "");
  var $x_4 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_Route$();
  var $x_3 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dLegacy$002dImport$0024.$m_Lquasar_frontend_QuasarApp$Page$LegacyImport$();
  var this$5 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_package$().Lcom_raquo_waypoint_Waypoint__f_root);
  var this$3 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_package$();
  var fromString = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lurldsl_vocabulary_FromString$().stringFromString__Lurldsl_vocabulary_FromString();
  var printer = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lurldsl_vocabulary_Printer$().stringPrinter__Lurldsl_vocabulary_Printer();
  var that$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lurldsl_language_PathSegmentImpl__unaryPathSegment__O__Lurldsl_vocabulary_FromString__Lurldsl_vocabulary_Printer__Lurldsl_language_PathSegment(this$3, "legacy-import", fromString, printer);
  var c$1 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lapp_tulz_tuplez_Composition$$anon$67();
  var this$7 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lurldsl_language_PathSegment__$div__Lurldsl_language_PathSegment__Lapp_tulz_tuplez_Composition__Lurldsl_language_PathSegment(this$5, that$1, c$1));
  var that$2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_package$().endOfSegments__Lurldsl_language_PathSegment();
  var c$2 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lapp_tulz_tuplez_Composition$$anon$67();
  this.Lquasar_frontend_QuasarApp$__f_legacyImportRoute = $x_4.static__O__Lurldsl_language_PathSegment__T__Lcom_raquo_waypoint_Route($x_3, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lurldsl_language_PathSegment__$div__Lurldsl_language_PathSegment__Lapp_tulz_tuplez_Composition__Lurldsl_language_PathSegment(this$7, that$2, c$2), "");
  var routes$1 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sci_$colon$colon(this.Lquasar_frontend_QuasarApp$__f_homeRoute, new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sci_$colon$colon(this.Lquasar_frontend_QuasarApp$__f_legacyImportRoute, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sci_Nil$()));
  var getPageTitle$1 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((x$1) => {
    var x$1$1 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage.$as_Lquasar_frontend_QuasarApp$Page(x$1);
    var x = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dHome$0024.$m_Lquasar_frontend_QuasarApp$Page$Home$();
    if ((x === x$1$1)) {
      return "Quasar - Home";
    }
    var x$3 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dLegacy$002dImport$0024.$m_Lquasar_frontend_QuasarApp$Page$LegacyImport$();
    if ((x$3 === x$1$1)) {
      return "Quasar - Legacy import";
    }
    throw new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_MatchError(x$1$1);
  }));
  var serializePage$1 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((x$1$2) => {
    var x$1$3 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage.$as_Lquasar_frontend_QuasarApp$Page(x$1$2);
    var x$2 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dHome$0024.$m_Lquasar_frontend_QuasarApp$Page$Home$();
    if ((x$2 === x$1$3)) {
      return "#/";
    }
    var x$3$1 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dLegacy$002dImport$0024.$m_Lquasar_frontend_QuasarApp$Page$LegacyImport$();
    if ((x$3$1 === x$1$3)) {
      return "#/legacy-import";
    }
    throw new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_MatchError(x$1$3);
  }));
  var deserializePage$1 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((x$1$3$1) => {
    var x$1$4 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_T(x$1$3$1);
    var this$9 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x$1$4);
    return $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage.$as_Lquasar_frontend_QuasarApp$Page((($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$uI(this$9.indexOf("legacy-import")) !== (-1)) ? $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dLegacy$002dImport$0024.$m_Lquasar_frontend_QuasarApp$Page$LegacyImport$() : $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dHome$0024.$m_Lquasar_frontend_QuasarApp$Page$Home$()));
  }));
  $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_Router$();
  var routeFallback$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_Router$().Lcom_raquo_waypoint_Router$__f_throwOnUnknownUrl;
  $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_Router$();
  var deserializeFallback$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_Router$().Lcom_raquo_waypoint_Router$__f_throwOnInvalidState;
  var this$12 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var x0 = this$12.Lcom_raquo_laminar_api_package$$anon$1__f_windowEventProps;
  $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var eventProp = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_Lcom_raquo_laminar_defs_eventProps_WindowEventProps(x0)).onPopState__Lcom_raquo_laminar_keys_EventProp();
  var p = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_keys_EventProcessor$().empty__Lcom_raquo_laminar_keys_EventProp__Z__Z__Lcom_raquo_laminar_keys_EventProcessor(eventProp, false, false);
  var this$17 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_airstream_web_DomEventStream$().apply__Lorg_scalajs_dom_EventTarget__T__Z__Lcom_raquo_airstream_core_EventStream(window, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(p).Lcom_raquo_laminar_keys_EventProcessor__f_eventProp).Lcom_raquo_laminar_keys_EventProp__f_name, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(p).Lcom_raquo_laminar_keys_EventProcessor__f_shouldUseCapture));
  var fn = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(p).Lcom_raquo_laminar_keys_EventProcessor__f_processor;
  this.Lquasar_frontend_QuasarApp$__f_router = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_waypoint_Router(routes$1, serializePage$1, deserializePage$1, getPageTitle$1, routeFallback$1, deserializeFallback$1, new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_airstream_misc_CollectStream(this$17, fn), $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).unsafeWindowOwner__Lcom_raquo_laminar_api_Laminar$unsafeWindowOwner$(), ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_Router$(), $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_Router$().Lcom_raquo_waypoint_Router$__f_canonicalDocumentOrigin), $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_waypoint_Router$().$lessinit$greater$default$10__sci_List__F1__F1__F1__F1__F1__T(routes$1, serializePage$1, deserializePage$1, getPageTitle$1, routeFallback$1, deserializeFallback$1));
}
export { $c_Lquasar_frontend_QuasarApp$ as $c_Lquasar_frontend_QuasarApp$ };
$c_Lquasar_frontend_QuasarApp$.prototype = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$h_O();
$c_Lquasar_frontend_QuasarApp$.prototype.constructor = $c_Lquasar_frontend_QuasarApp$;
/** @constructor */
function $h_Lquasar_frontend_QuasarApp$() {
}
export { $h_Lquasar_frontend_QuasarApp$ as $h_Lquasar_frontend_QuasarApp$ };
$h_Lquasar_frontend_QuasarApp$.prototype = $c_Lquasar_frontend_QuasarApp$.prototype;
$c_Lquasar_frontend_QuasarApp$.prototype.apply__T__T__Lcom_raquo_laminar_nodes_ReactiveHtmlElement = (function(baseUrl, docsBase) {
  var http = new $j_quasar$002efrontend$002e$002dBrowser$002dHttp$002dClient.$c_Lquasar_frontend_BrowserHttpClient(baseUrl);
  var api = new $j_quasar$002efrontend$002e$002dQuasar$002dApi.$c_Lquasar_frontend_QuasarApi(baseUrl, http);
  var trimmed = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_T__trim__T($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(docsBase));
  var this$3 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(trimmed);
  if (((this$3 === "") || (trimmed === "/"))) {
    var docsBaseNormalized = "";
  } else {
    var this$4 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(trimmed);
    if ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$uZ(this$4.endsWith("/"))) {
      var docsBaseNormalized = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sc_StringOps$().dropRight$extension__T__I__T(trimmed, 1);
    } else {
      var docsBaseNormalized = trimmed;
    }
  }
  var $x_37 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).div__Lcom_raquo_laminar_tags_HtmlTag());
  var $x_36 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_35 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("graviton-app quasar-app");
  var $x_34 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_laminar_tags_HtmlTag("header", false);
  var $x_33 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_32 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("app-header");
  var $x_31 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).div__Lcom_raquo_laminar_tags_HtmlTag();
  var $x_30 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_29 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("header-content");
  var $x_28 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).h1__Lcom_raquo_laminar_tags_HtmlTag());
  var $x_27 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_26 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("app-title");
  var this$6 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var r = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
  var $x_25 = $x_28.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_27.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_26, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$6, "Quasar", r)])));
  var $x_24 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).p__Lcom_raquo_laminar_tags_HtmlTag();
  var $x_23 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_22 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("app-subtitle");
  var this$7 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var r$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
  var $x_21 = $x_34.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_33.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_32, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($x_31).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_30.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_29, $x_25, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($x_24).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_23.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_22, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$7, "Document platform (Scala.js + Laminar)", r$1)])))]))), new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_laminar_tags_HtmlTag("nav", false).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$().wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("app-nav"), $p_Lquasar_frontend_QuasarApp$__navLink__Lquasar_frontend_QuasarApp$Page__T__Lcom_raquo_laminar_nodes_ReactiveHtmlElement(this, $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dHome$0024.$m_Lquasar_frontend_QuasarApp$Page$Home$(), "\ud83c\udfe0 Home"), $p_Lquasar_frontend_QuasarApp$__navLink__Lquasar_frontend_QuasarApp$Page__T__Lcom_raquo_laminar_nodes_ReactiveHtmlElement(this, $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage$0024$002dLegacy$002dImport$0024.$m_Lquasar_frontend_QuasarApp$Page$LegacyImport$(), "\ud83e\uddfe Legacy import")]))), $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).div__Lcom_raquo_laminar_tags_HtmlTag()).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$().wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("header-health"), $j_quasar$002efrontend$002ecomponents$002e$002dHealth$002dCheck$0024.$m_Lquasar_frontend_components_HealthCheck$().apply__Lquasar_frontend_QuasarApi__Lcom_raquo_laminar_nodes_ReactiveHtmlElement(api)])))])));
  var $x_20 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_laminar_tags_HtmlTag("main", false);
  var $x_19 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_18 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("app-content");
  $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_child);
  var this$8 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(this.Lquasar_frontend_QuasarApp$__f_router).Lcom_raquo_waypoint_Router__f_currentPageSignal);
  var project = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((page) => {
    var page$1 = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024$002dPage.$as_Lquasar_frontend_QuasarApp$Page(page);
    return $p_Lquasar_frontend_QuasarApp$__renderPage__Lquasar_frontend_QuasarApp$Page__Lquasar_frontend_QuasarApi__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($m_Lquasar_frontend_QuasarApp$(), page$1, api);
  }));
  var childSource = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$ct_Lcom_raquo_airstream_misc_MapSignal__Lcom_raquo_airstream_core_Signal__F1__s_Option__(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_airstream_misc_MapSignal(), this$8, project, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_s_None$());
  var $x_17 = $x_20.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_19.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_18, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_inserters_ChildInserter$().apply__Lcom_raquo_airstream_core_Observable__Lcom_raquo_laminar_modifiers_RenderableNode__O__Lcom_raquo_laminar_inserters_DynamicInserter(childSource, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableNode$().Lcom_raquo_laminar_modifiers_RenderableNode$__f_nodeRenderable, (void 0))])));
  var $x_16 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_laminar_tags_HtmlTag("footer", false);
  var $x_15 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_14 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("app-footer");
  var $x_13 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).p__Lcom_raquo_laminar_tags_HtmlTag());
  var $x_12 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var this$10 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var r$2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
  var $x_11 = $x_13.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_12.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$10, "Built with ZIO \u2022 Powered by Scala 3 \u2022 UI with Laminar", r$2)])));
  var $x_10 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).p__Lcom_raquo_laminar_tags_HtmlTag();
  var $x_9 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_8 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).a__Lcom_raquo_laminar_tags_HtmlTag());
  var $x_7 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_6 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).href__Lcom_raquo_laminar_keys_HtmlAttr()).$colon$eq__O__Lcom_raquo_laminar_modifiers_KeySetter($p_Lquasar_frontend_QuasarApp$__docHref$1__T__T__T(this, docsBaseNormalized, "/api/quasar-http-v1"));
  var this$11 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var r$3 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
  var $x_5 = $x_8.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_7.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_6, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$11, "Quasar API docs", r$3)])));
  var this$12 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var r$4 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
  var $x_4 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$12, " \u2022 ", r$4);
  var $x_3 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).a__Lcom_raquo_laminar_tags_HtmlTag();
  var $x_2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$();
  var $x_1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).href__Lcom_raquo_laminar_keys_HtmlAttr()).$colon$eq__O__Lcom_raquo_laminar_modifiers_KeySetter($p_Lquasar_frontend_QuasarApp$__docHref$1__T__T__T(this, docsBaseNormalized, "/api/legacy-repos"));
  var this$13 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var r$5 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
  return $x_37.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_36.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_35, $x_21, $x_17, $x_16.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_15.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_14, $x_11, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($x_10).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_9.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_5, $x_4, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($x_3).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_2.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_1, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$13, "Legacy repos", r$5)])))])))])))])));
});
var $d_Lquasar_frontend_QuasarApp$ = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$TypeData().initClass($c_Lquasar_frontend_QuasarApp$, "quasar.frontend.QuasarApp$", ({
  Lquasar_frontend_QuasarApp$: 1
}));
export { $d_Lquasar_frontend_QuasarApp$ as $d_Lquasar_frontend_QuasarApp$ };
var $n_Lquasar_frontend_QuasarApp$;
function $m_Lquasar_frontend_QuasarApp$() {
  if ((!$n_Lquasar_frontend_QuasarApp$)) {
    $n_Lquasar_frontend_QuasarApp$ = new $c_Lquasar_frontend_QuasarApp$();
  }
  return $n_Lquasar_frontend_QuasarApp$;
}
export { $m_Lquasar_frontend_QuasarApp$ as $m_Lquasar_frontend_QuasarApp$ };
//# sourceMappingURL=quasar.frontend.-Quasar-App$.js.map

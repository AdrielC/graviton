'use strict';
import * as $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6 from "./internal-3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.js";
import * as $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c from "./internal-9eb04c7a932b923c3bc5eb25fd6219ee930b689c.js";
function $p_Lgraviton_frontend_components_HealthCheck$__statusEmoji__T__T($thiz, status) {
  var this$1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(status);
  var x12 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_T(this$1.toLowerCase());
  switch (x12) {
    case "healthy":
    case "ok": {
      return "\u2705";
      break;
    }
    case "degraded": {
      return "\u26a0\ufe0f";
      break;
    }
    default: {
      return "\u274c";
    }
  }
}
export { $p_Lgraviton_frontend_components_HealthCheck$__statusEmoji__T__T as $p_Lgraviton_frontend_components_HealthCheck$__statusEmoji__T__T };
function $p_Lgraviton_frontend_components_HealthCheck$__formatUptime__J__T($thiz, ms) {
  var this$1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_RTLong$();
  var lo = this$1.divideImpl__I__I__I__I__I(ms.RTLong__f_lo, ms.RTLong__f_hi, 1000, 0);
  var hi = this$1.RTLong$__f_org$scalajs$linker$runtime$RuntimeLong$$hiReturn;
  var this$2 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_RTLong$();
  var lo$1 = this$2.divideImpl__I__I__I__I__I(lo, hi, 60, 0);
  var hi$1 = this$2.RTLong$__f_org$scalajs$linker$runtime$RuntimeLong$$hiReturn;
  var this$3 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_RTLong$();
  var lo$2 = this$3.divideImpl__I__I__I__I__I(lo$1, hi$1, 60, 0);
  var hi$2 = this$3.RTLong$__f_org$scalajs$linker$runtime$RuntimeLong$$hiReturn;
  var this$4 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_RTLong$();
  var lo$3 = this$4.divideImpl__I__I__I__I__I(lo$2, hi$2, 24, 0);
  var hi$3 = this$4.RTLong$__f_org$scalajs$linker$runtime$RuntimeLong$$hiReturn;
  if (((hi$3 === 0) ? (lo$3 !== 0) : (hi$3 > 0))) {
    var $x_1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_RTLong(lo$3, hi$3);
    var this$5 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_RTLong$();
    var lo$4 = this$5.remainderImpl__I__I__I__I__I(lo$2, hi$2, 24, 0);
    var hi$4 = this$5.RTLong$__f_org$scalajs$linker$runtime$RuntimeLong$$hiReturn;
    return ((($x_1 + "d ") + new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_RTLong(lo$4, hi$4)) + "h");
  } else if (((hi$2 === 0) ? (lo$2 !== 0) : (hi$2 > 0))) {
    var $x_2 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_RTLong(lo$2, hi$2);
    var this$6 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_RTLong$();
    var lo$5 = this$6.remainderImpl__I__I__I__I__I(lo$1, hi$1, 60, 0);
    var hi$5 = this$6.RTLong$__f_org$scalajs$linker$runtime$RuntimeLong$$hiReturn;
    return ((($x_2 + "h ") + new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_RTLong(lo$5, hi$5)) + "m");
  } else if (((hi$1 === 0) ? (lo$1 !== 0) : (hi$1 > 0))) {
    var $x_3 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_RTLong(lo$1, hi$1);
    var this$7 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_RTLong$();
    var lo$6 = this$7.remainderImpl__I__I__I__I__I(lo, hi, 60, 0);
    var hi$6 = this$7.RTLong$__f_org$scalajs$linker$runtime$RuntimeLong$$hiReturn;
    return ((($x_3 + "m ") + new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_RTLong(lo$6, hi$6)) + "s");
  } else {
    return (new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_RTLong(lo, hi) + "s");
  }
}
export { $p_Lgraviton_frontend_components_HealthCheck$__formatUptime__J__T as $p_Lgraviton_frontend_components_HealthCheck$__formatUptime__J__T };
function $p_Lgraviton_frontend_components_HealthCheck$__checkHealth$1__Lcom_raquo_airstream_state_Var__Lcom_raquo_airstream_state_Var__Lzio_Runtime__Lgraviton_frontend_GravitonApi__Lcom_raquo_airstream_state_Var__V($thiz, loadingVar$1, errorVar$1, runtime$1, api$1, healthVar$1) {
  var this$1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(loadingVar$1);
  $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_airstream_state_Var__set__O__V(this$1, true);
  var this$2 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(errorVar$1);
  var value = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_None$();
  $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_airstream_state_Var__set__O__V(this$2, value);
  var this$3 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_Unsafe$();
  var this$4 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(runtime$1);
  var this$12 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lzio_Runtime$$anon$1(this$4).runToFuture__Lzio_ZIO__O__Lzio_Unsafe__Lzio_CancelableFuture($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(api$1).getHealth__Lzio_ZIO(), "graviton.frontend.components.HealthCheck.apply.checkHealth(HealthCheck.scala:24)", this$3));
  var f = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((x$1) => {
    var x$1$1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_s_util_Try(x$1);
    matchResult1: {
      if ((x$1$1 instanceof $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_s_util_Success)) {
        var x4 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_s_util_Success(x$1$1);
        var health = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_Lgraviton_shared_ApiModels$HealthResponse($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(x4).s_util_Success__f_value);
        var this$6 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(healthVar$1);
        var value$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_s_Some(health);
        $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_airstream_state_Var__set__O__V(this$6, value$1);
        var this$7 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(loadingVar$1);
        $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_airstream_state_Var__set__O__V(this$7, false);
        break matchResult1;
      }
      if ((x$1$1 instanceof $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_s_util_Failure)) {
        var x2 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_s_util_Failure(x$1$1);
        var error = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(x2).s_util_Failure__f_exception;
        var this$9 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(errorVar$1);
        var value$2 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(error).getMessage__T();
        var value$3 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_s_Some(value$2);
        $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_airstream_state_Var__set__O__V(this$9, value$3);
        var this$10 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(loadingVar$1);
        $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_airstream_state_Var__set__O__V(this$10, false);
        break matchResult1;
      }
      throw new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_s_MatchError(x$1$1);
    }
  }));
  var executor = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_concurrent_ExecutionContext$().global__s_concurrent_ExecutionContextExecutor();
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(this$12.Lzio_CancelableFuture__f_future).onComplete__F1__s_concurrent_ExecutionContext__V(f, executor);
}
export { $p_Lgraviton_frontend_components_HealthCheck$__checkHealth$1__Lcom_raquo_airstream_state_Var__Lcom_raquo_airstream_state_Var__Lzio_Runtime__Lgraviton_frontend_GravitonApi__Lcom_raquo_airstream_state_Var__V as $p_Lgraviton_frontend_components_HealthCheck$__checkHealth$1__Lcom_raquo_airstream_state_Var__Lcom_raquo_airstream_state_Var__Lzio_Runtime__Lgraviton_frontend_GravitonApi__Lcom_raquo_airstream_state_Var__V };
/** @constructor */
function $c_Lgraviton_frontend_components_HealthCheck$() {
}
export { $c_Lgraviton_frontend_components_HealthCheck$ as $c_Lgraviton_frontend_components_HealthCheck$ };
$c_Lgraviton_frontend_components_HealthCheck$.prototype = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$h_O();
$c_Lgraviton_frontend_components_HealthCheck$.prototype.constructor = $c_Lgraviton_frontend_components_HealthCheck$;
/** @constructor */
function $h_Lgraviton_frontend_components_HealthCheck$() {
}
export { $h_Lgraviton_frontend_components_HealthCheck$ as $h_Lgraviton_frontend_components_HealthCheck$ };
$h_Lgraviton_frontend_components_HealthCheck$.prototype = $c_Lgraviton_frontend_components_HealthCheck$.prototype;
$c_Lgraviton_frontend_components_HealthCheck$.prototype.apply__Lgraviton_frontend_GravitonApi__Lcom_raquo_laminar_nodes_ReactiveHtmlElement = (function(api) {
  var healthVar = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_Var).apply__O__Lcom_raquo_airstream_state_Var($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_None$());
  var loadingVar = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_Var).apply__O__Lcom_raquo_airstream_state_Var(true);
  var errorVar = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_Var).apply__O__Lcom_raquo_airstream_state_Var($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_None$());
  var runtime = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_Runtime$().Lzio_Runtime$__f_default;
  $p_Lgraviton_frontend_components_HealthCheck$__checkHealth$1__Lcom_raquo_airstream_state_Var__Lcom_raquo_airstream_state_Var__Lzio_Runtime__Lgraviton_frontend_GravitonApi__Lcom_raquo_airstream_state_Var__V(this, loadingVar, errorVar, runtime, api, healthVar);
  var $x_27 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).div__Lcom_raquo_laminar_tags_HtmlTag());
  var $x_26 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sr_ScalaRunTime$();
  var $x_25 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("health-check");
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_child);
  var this$6 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(healthVar).Lcom_raquo_airstream_state_SourceVar__f_signal);
  var project = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((x$1) => {
    var x$1$1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_s_Option(x$1);
    var x = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_None$();
    if ((x === x$1$1)) {
      $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
      return new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_laminar_nodes_CommentNode("");
    }
    if ((x$1$1 instanceof $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_s_Some)) {
      var x7 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_s_Some(x$1$1);
      var health = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_Lgraviton_shared_ApiModels$HealthResponse($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(x7).s_Some__f_value);
      var $x_23 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).div__Lcom_raquo_laminar_tags_HtmlTag());
      var $x_22 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sr_ScalaRunTime$();
      var $x_21 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("health-status");
      var $x_20 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).div__Lcom_raquo_laminar_tags_HtmlTag());
      var $x_19 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sr_ScalaRunTime$();
      var $x_18 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls);
      var this$2 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(health).Lgraviton_shared_ApiModels$HealthResponse__f_status);
      var $x_17 = $x_18.$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter(("status-badge status-" + $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_T(this$2.toLowerCase())));
      var this$3 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
      var value = (($p_Lgraviton_frontend_components_HealthCheck$__statusEmoji__T__T($m_Lgraviton_frontend_components_HealthCheck$(), $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(health).Lgraviton_shared_ApiModels$HealthResponse__f_status) + " ") + $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(health).Lgraviton_shared_ApiModels$HealthResponse__f_status);
      var r = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
      var $x_16 = $x_20.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_19.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_17, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$3, value, r)])));
      var $x_15 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).div__Lcom_raquo_laminar_tags_HtmlTag();
      var $x_14 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sr_ScalaRunTime$();
      var $x_13 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("health-details");
      var $x_12 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).p__Lcom_raquo_laminar_tags_HtmlTag());
      var $x_11 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sr_ScalaRunTime$();
      var this$4 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
      var value$1 = ("Version: " + $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(health).Lgraviton_shared_ApiModels$HealthResponse__f_version);
      var r$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
      var $x_10 = $x_12.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_11.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$4, value$1, r$1)])));
      var $x_9 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).p__Lcom_raquo_laminar_tags_HtmlTag();
      var $x_8 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sr_ScalaRunTime$();
      var this$5 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
      var value$2 = ("Uptime: " + $p_Lgraviton_frontend_components_HealthCheck$__formatUptime__J__T($m_Lgraviton_frontend_components_HealthCheck$(), $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(health).Lgraviton_shared_ApiModels$HealthResponse__f_uptime));
      var r$2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
      return $x_23.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_22.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_21, $x_16, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($x_15).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_14.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_13, $x_10, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($x_9).apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_8.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$5, value$2, r$2)])))])))])));
    }
    throw new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_s_MatchError(x$1$1);
  }));
  var childSource = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$ct_Lcom_raquo_airstream_misc_MapSignal__Lcom_raquo_airstream_core_Signal__F1__s_Option__(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_airstream_misc_MapSignal(), this$6, project, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_None$());
  var $x_24 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_inserters_ChildInserter$().apply__Lcom_raquo_airstream_core_Observable__Lcom_raquo_laminar_modifiers_RenderableNode__O__Lcom_raquo_laminar_inserters_DynamicInserter(childSource, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableNode$().Lcom_raquo_laminar_modifiers_RenderableNode$__f_nodeRenderable, (void 0));
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_child);
  var this$10 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(errorVar).Lcom_raquo_airstream_state_SourceVar__f_signal);
  var project$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((x$1$2) => {
    var x$1$3 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_s_Option(x$1$2);
    var x$2 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_None$();
    if ((x$2 === x$1$3)) {
      $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
      return new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_laminar_nodes_CommentNode("");
    }
    if ((x$1$3 instanceof $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_s_Some)) {
      var x10 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_s_Some(x$1$3);
      $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_T($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(x10).s_Some__f_value);
      var $x_6 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).div__Lcom_raquo_laminar_tags_HtmlTag());
      var $x_5 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sr_ScalaRunTime$();
      var $x_4 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("status-badge status-error");
      var this$9 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
      var r$3 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
      return $x_6.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_5.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_4, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$9, "\u274c Offline", r$3)])));
    }
    throw new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_s_MatchError(x$1$3);
  }));
  var childSource$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$ct_Lcom_raquo_airstream_misc_MapSignal__Lcom_raquo_airstream_core_Signal__F1__s_Option__(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_airstream_misc_MapSignal(), this$10, project$1, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_None$());
  var $x_7 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_inserters_ChildInserter$().apply__Lcom_raquo_airstream_core_Observable__Lcom_raquo_laminar_modifiers_RenderableNode__O__Lcom_raquo_laminar_inserters_DynamicInserter(childSource$1, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableNode$().Lcom_raquo_laminar_modifiers_RenderableNode$__f_nodeRenderable, (void 0));
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_child);
  var this$14 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(loadingVar).Lcom_raquo_airstream_state_SourceVar__f_signal);
  var project$2 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((loading) => {
    var loading$1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$uZ(loading);
    if (loading$1) {
      var $x_3 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).span__Lcom_raquo_laminar_tags_HtmlTag());
      var $x_2 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sr_ScalaRunTime$();
      var $x_1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L).Lcom_raquo_laminar_api_package$$anon$1__f_cls).$colon$eq__T__Lcom_raquo_laminar_modifiers_CompositeKeySetter("status-loading");
      var this$12 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
      var r$4 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableText$().Lcom_raquo_laminar_modifiers_RenderableText$__f_stringRenderable;
      return $x_3.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_2.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_1, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_laminar_api_Implicits__textToTextNode__O__Lcom_raquo_laminar_modifiers_RenderableText__Lcom_raquo_laminar_nodes_TextNode(this$12, "\u23f3", r$4)])));
    } else {
      $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
      return new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_laminar_nodes_CommentNode("");
    }
  }));
  var childSource$2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$ct_Lcom_raquo_airstream_misc_MapSignal__Lcom_raquo_airstream_core_Signal__F1__s_Option__(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_airstream_misc_MapSignal(), this$14, project$2, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_None$());
  return $x_27.apply__sci_Seq__Lcom_raquo_laminar_nodes_ReactiveHtmlElement($x_26.wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lcom_raquo_laminar_modifiers_Modifier.getArrayOf().constr)([$x_25, $x_24, $x_7, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_inserters_ChildInserter$().apply__Lcom_raquo_airstream_core_Observable__Lcom_raquo_laminar_modifiers_RenderableNode__O__Lcom_raquo_laminar_inserters_DynamicInserter(childSource$2, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_modifiers_RenderableNode$().Lcom_raquo_laminar_modifiers_RenderableNode$__f_nodeRenderable, (void 0))])));
});
var $d_Lgraviton_frontend_components_HealthCheck$ = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$TypeData().initClass($c_Lgraviton_frontend_components_HealthCheck$, "graviton.frontend.components.HealthCheck$", ({
  Lgraviton_frontend_components_HealthCheck$: 1
}));
export { $d_Lgraviton_frontend_components_HealthCheck$ as $d_Lgraviton_frontend_components_HealthCheck$ };
var $n_Lgraviton_frontend_components_HealthCheck$;
function $m_Lgraviton_frontend_components_HealthCheck$() {
  if ((!$n_Lgraviton_frontend_components_HealthCheck$)) {
    $n_Lgraviton_frontend_components_HealthCheck$ = new $c_Lgraviton_frontend_components_HealthCheck$();
  }
  return $n_Lgraviton_frontend_components_HealthCheck$;
}
export { $m_Lgraviton_frontend_components_HealthCheck$ as $m_Lgraviton_frontend_components_HealthCheck$ };
//# sourceMappingURL=graviton.frontend.components.-Health-Check$.js.map

'use strict';
import * as $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6 from "./internal-3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.js";
import * as $j_quasar$002efrontend$002e$002dBrowser$002dHttp$002dClient from "./quasar.frontend.-Browser-Http-Client.js";
import * as $j_quasar$002efrontend$002e$002dLegacy$002dImport$002dRequest from "./quasar.frontend.-Legacy-Import-Request.js";
import * as $j_quasar$002efrontend$002e$002dLegacy$002dImport$002dRequest$0024 from "./quasar.frontend.-Legacy-Import-Request$.js";
import * as $j_quasar$002efrontend$002e$002dLegacy$002dImport$002dResponse from "./quasar.frontend.-Legacy-Import-Response.js";
import * as $j_quasar$002efrontend$002e$002dLegacy$002dImport$002dResponse$0024 from "./quasar.frontend.-Legacy-Import-Response$.js";
/** @constructor */
function $c_Lquasar_frontend_QuasarApi(baseUrl, http) {
  this.Lquasar_frontend_QuasarApi__f_baseUrl = null;
  this.Lquasar_frontend_QuasarApi__f_http = null;
  this.Lquasar_frontend_QuasarApi__f_baseUrl = baseUrl;
  this.Lquasar_frontend_QuasarApi__f_http = http;
}
export { $c_Lquasar_frontend_QuasarApi as $c_Lquasar_frontend_QuasarApi };
$c_Lquasar_frontend_QuasarApi.prototype = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$h_O();
$c_Lquasar_frontend_QuasarApi.prototype.constructor = $c_Lquasar_frontend_QuasarApi;
/** @constructor */
function $h_Lquasar_frontend_QuasarApi() {
}
export { $h_Lquasar_frontend_QuasarApi as $h_Lquasar_frontend_QuasarApi };
$h_Lquasar_frontend_QuasarApi.prototype = $c_Lquasar_frontend_QuasarApi.prototype;
$c_Lquasar_frontend_QuasarApi.prototype.productIterator__sc_Iterator = (function() {
  return new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_Product$$anon$1(this);
});
$c_Lquasar_frontend_QuasarApi.prototype.hashCode__I = (function() {
  return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_s_util_hashing_MurmurHash3$().productHash__s_Product__I__Z__I(this, (-1249128046), true);
});
$c_Lquasar_frontend_QuasarApi.prototype.equals__O__Z = (function(x$0) {
  if ((this === x$0)) {
    return true;
  } else if ((x$0 instanceof $c_Lquasar_frontend_QuasarApi)) {
    var x10 = $as_Lquasar_frontend_QuasarApi(x$0);
    if ((this.Lquasar_frontend_QuasarApi__f_baseUrl === $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x10).Lquasar_frontend_QuasarApi__f_baseUrl)) {
      var x = this.Lquasar_frontend_QuasarApi__f_http;
      var x$2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x10).Lquasar_frontend_QuasarApi__f_http;
      return ((x === null) ? (x$2 === null) : $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x).equals__O__Z(x$2));
    } else {
      return false;
    }
  } else {
    return false;
  }
});
$c_Lquasar_frontend_QuasarApi.prototype.toString__T = (function() {
  return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$()._toString__s_Product__T(this);
});
$c_Lquasar_frontend_QuasarApi.prototype.productArity__I = (function() {
  return 2;
});
$c_Lquasar_frontend_QuasarApi.prototype.productPrefix__T = (function() {
  return "QuasarApi";
});
$c_Lquasar_frontend_QuasarApi.prototype.productElement__I__O = (function(n) {
  if ((n === 0)) {
    return this.Lquasar_frontend_QuasarApi__f_baseUrl;
  }
  if ((n === 1)) {
    return this.Lquasar_frontend_QuasarApi__f_http;
  }
  throw $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$ct_jl_IndexOutOfBoundsException__T__(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_jl_IndexOutOfBoundsException(), ("" + n));
});
$c_Lquasar_frontend_QuasarApi.prototype.health__Lzio_ZIO = (function() {
  var this$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(this.Lquasar_frontend_QuasarApi__f_http);
  var this$4 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_quasar$002efrontend$002e$002dBrowser$002dHttp$002dClient.$p_Lquasar_frontend_BrowserHttpClient__fetch__T__T__s_Option__Lzio_ZIO(this$1, "/v1/health", "GET", $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_s_None$())).as__F0__O__Lzio_ZIO(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => true)), "quasar.frontend.QuasarApi.health(QuasarApi.scala:25)"));
  var h = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((_$1) => {
    $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_jl_Throwable(_$1);
    $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_ZIO$();
    $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_ZIO$();
    var eval$1 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => false));
    return new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lzio_ZIO$Sync("quasar.frontend.QuasarApi.health(QuasarApi.scala:25)", eval$1);
  }));
  var ev$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_CanFail$();
  return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lzio_ZIO__foldZIO__F1__F1__Lzio_CanFail__O__Lzio_ZIO(this$4, h, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_ZIO$().Lzio_ZIO$__f__SuccessFn, ev$1, "quasar.frontend.QuasarApi.health(QuasarApi.scala:25)");
});
$c_Lquasar_frontend_QuasarApi.prototype.legacyImport__T__T__Lzio_ZIO = (function(legacyRepo, legacyDocId) {
  var $x_2 = this.Lquasar_frontend_QuasarApi__f_http;
  var mode = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_s_None$();
  var a = new $j_quasar$002efrontend$002e$002dLegacy$002dImport$002dRequest.$c_Lquasar_frontend_LegacyImportRequest(legacyRepo, legacyDocId, mode);
  var $x_1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_json_package$EncoderOps$();
  $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_json_JsonEncoder$();
  var codec = $j_quasar$002efrontend$002e$002dLegacy$002dImport$002dRequest$0024.$m_Lquasar_frontend_LegacyImportRequest$().derived$JsonCodec__Lzio_json_JsonCodec();
  return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($x_2).post__T__T__Lzio_ZIO("/v1/legacy/import", $x_1.toJson$extension__O__Lzio_json_JsonEncoder__T(a, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(codec).Lzio_json_JsonCodec__f_encoder))).flatMap__F1__O__Lzio_ZIO(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((raw) => {
    var raw$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_T(raw);
    $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_json_JsonDecoder$();
    var codec$1 = $j_quasar$002efrontend$002e$002dLegacy$002dImport$002dResponse$0024.$m_Lquasar_frontend_LegacyImportResponse$().derived$JsonCodec__Lzio_json_JsonCodec();
    var decoder = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(codec$1).Lzio_json_JsonCodec__f_decoder;
    var x13 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lzio_json_JsonDecoder__decodeJson__jl_CharSequence__s_util_Either($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(decoder), raw$1);
    if ((x13 instanceof $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_util_Left)) {
      var x16 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_s_util_Left(x13);
      var err = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_T($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x16).s_util_Left__f_value);
      return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_ZIO$().fail__F0__O__Lzio_ZIO(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$ct_jl_Exception__T__(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_jl_Exception(), err))), "quasar.frontend.QuasarApi.legacyImport(QuasarApi.scala:35)");
    }
    if ((x13 instanceof $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_util_Right)) {
      var x14 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_s_util_Right(x13);
      var resp = $j_quasar$002efrontend$002e$002dLegacy$002dImport$002dResponse.$as_Lquasar_frontend_LegacyImportResponse($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x14).s_util_Right__f_value);
      $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_ZIO$();
      $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_ZIO$();
      var eval$1 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => resp));
      return new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lzio_ZIO$Sync("quasar.frontend.QuasarApi.legacyImport(QuasarApi.scala:36)", eval$1);
    }
    throw new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_MatchError(x13);
  })), "quasar.frontend.QuasarApi.legacyImport(QuasarApi.scala:37)");
});
function $as_Lquasar_frontend_QuasarApi(obj) {
  return (((obj instanceof $c_Lquasar_frontend_QuasarApi) || (obj === null)) ? obj : $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$throwClassCastException(obj, "quasar.frontend.QuasarApi"));
}
export { $as_Lquasar_frontend_QuasarApi as $as_Lquasar_frontend_QuasarApi };
function $isArrayOf_Lquasar_frontend_QuasarApi(obj, depth) {
  return (!(!(((obj && obj.$classData) && (obj.$classData.arrayDepth === depth)) && obj.$classData.arrayBase.ancestors.Lquasar_frontend_QuasarApi)));
}
export { $isArrayOf_Lquasar_frontend_QuasarApi as $isArrayOf_Lquasar_frontend_QuasarApi };
function $asArrayOf_Lquasar_frontend_QuasarApi(obj, depth) {
  return (($isArrayOf_Lquasar_frontend_QuasarApi(obj, depth) || (obj === null)) ? obj : $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$throwArrayCastException(obj, "Lquasar.frontend.QuasarApi;", depth));
}
export { $asArrayOf_Lquasar_frontend_QuasarApi as $asArrayOf_Lquasar_frontend_QuasarApi };
var $d_Lquasar_frontend_QuasarApi = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$TypeData().initClass($c_Lquasar_frontend_QuasarApi, "quasar.frontend.QuasarApi", ({
  Lquasar_frontend_QuasarApi: 1,
  s_Equals: 1,
  s_Product: 1,
  Ljava_io_Serializable: 1
}));
export { $d_Lquasar_frontend_QuasarApi as $d_Lquasar_frontend_QuasarApi };
//# sourceMappingURL=quasar.frontend.-Quasar-Api.js.map

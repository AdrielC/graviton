'use strict';
import * as $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c from "./internal-9eb04c7a932b923c3bc5eb25fd6219ee930b689c.js";
function $p_Lgraviton_frontend_BrowserHttpClient__fetch__T__T__s_Option__Lzio_ZIO($thiz, path, method, body) {
  var url = (("" + $thiz.Lgraviton_frontend_BrowserHttpClient__f_baseUrl) + path);
  var this$18 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZIO$();
  var promise = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => {
    var init = (() => {
      var this$1 = ({});
      return this$1;
    })();
    var $x_1;
    switch (method) {
      case "GET": {
        var $x_1 = "GET";
        break;
      }
      case "POST": {
        var $x_1 = "POST";
        break;
      }
      case "PUT": {
        var $x_1 = "PUT";
        break;
      }
      case "DELETE": {
        var $x_1 = "DELETE";
        break;
      }
      default: {
        var $x_1 = "GET";
      }
    }
    init.method = $x_1;
    var properties = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sr_ScalaRunTime$().wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$d_T2.getArrayOf().constr)([new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_T2("Content-Type", "application/json"), new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_T2("Accept", "application/json")]));
    init.headers = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sjs_js_special_package$().objectLiteral__sci_Seq__sjs_js_Object(properties);
    var this$7 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(body);
    if ((!this$7.isEmpty__Z())) {
      var x0 = this$7.get__O();
      var b = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_T(x0);
      init.body = b;
    }
    var p = fetch(url, init);
    var f = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sjs_js_Thenable$ThenableOps$().toFuture$extension__sjs_js_Thenable__s_concurrent_Future(p)).flatMap__F1__s_concurrent_ExecutionContext__s_concurrent_Future(new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((response) => {
      var p$1 = response.text();
      return $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sjs_js_Thenable$ThenableOps$().toFuture$extension__sjs_js_Thenable__s_concurrent_Future(p$1)).map__F1__s_concurrent_ExecutionContext__s_concurrent_Future(new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((text) => {
        var text$1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_T(text);
        if ($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$uZ(response.ok)) {
          return text$1;
        } else {
          throw $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$ct_jl_Exception__T__(new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_jl_Exception(), ((("HTTP " + $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$uI(response.status)) + ": ") + text$1));
        }
      })), $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_concurrent_ExecutionContext$().global__s_concurrent_ExecutionContextExecutor());
    })), $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_concurrent_ExecutionContext$().global__s_concurrent_ExecutionContextExecutor());
    return $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_sjs_js_JSConverters$JSRichFuture$().toJSPromise$extension__s_concurrent_Future__s_concurrent_ExecutionContext__sjs_js_Promise(f, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_s_concurrent_ExecutionContext$().global__s_concurrent_ExecutionContextExecutor());
  }));
  return $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$f_Lzio_ZIOCompanionPlatformSpecific__fromPromiseJS__F0__O__Lzio_ZIO(this$18, promise, "graviton.frontend.BrowserHttpClient.fetch(BrowserHttpClient.scala:42)");
}
export { $p_Lgraviton_frontend_BrowserHttpClient__fetch__T__T__s_Option__Lzio_ZIO as $p_Lgraviton_frontend_BrowserHttpClient__fetch__T__T__s_Option__Lzio_ZIO };
/** @constructor */
function $c_Lgraviton_frontend_BrowserHttpClient(baseUrl) {
  this.Lgraviton_frontend_BrowserHttpClient__f_baseUrl = null;
  this.Lgraviton_frontend_BrowserHttpClient__f_baseUrl = baseUrl;
}
export { $c_Lgraviton_frontend_BrowserHttpClient as $c_Lgraviton_frontend_BrowserHttpClient };
$c_Lgraviton_frontend_BrowserHttpClient.prototype = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$h_O();
$c_Lgraviton_frontend_BrowserHttpClient.prototype.constructor = $c_Lgraviton_frontend_BrowserHttpClient;
/** @constructor */
function $h_Lgraviton_frontend_BrowserHttpClient() {
}
export { $h_Lgraviton_frontend_BrowserHttpClient as $h_Lgraviton_frontend_BrowserHttpClient };
$h_Lgraviton_frontend_BrowserHttpClient.prototype = $c_Lgraviton_frontend_BrowserHttpClient.prototype;
var $d_Lgraviton_frontend_BrowserHttpClient = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$TypeData().initClass($c_Lgraviton_frontend_BrowserHttpClient, "graviton.frontend.BrowserHttpClient", ({
  Lgraviton_frontend_BrowserHttpClient: 1,
  Lgraviton_shared_HttpClient: 1
}));
export { $d_Lgraviton_frontend_BrowserHttpClient as $d_Lgraviton_frontend_BrowserHttpClient };
//# sourceMappingURL=graviton.frontend.-Browser-Http-Client.js.map

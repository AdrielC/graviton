'use strict';
import * as $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6 from "./internal-3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.js";
function $p_Lquasar_frontend_BrowserHttpClient__fetch__T__T__s_Option__Lzio_ZIO($thiz, path, method, body) {
  var url = (("" + $thiz.Lquasar_frontend_BrowserHttpClient__f_baseUrl) + path);
  var this$18 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_ZIO$();
  var promise = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => {
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
    var properties = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$().wrapRefArray__AO__sci_ArraySeq(new ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_T2.getArrayOf().constr)([new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_T2("Content-Type", "application/json"), new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_T2("Accept", "application/json")]));
    init.headers = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sjs_js_special_package$().objectLiteral__sci_Seq__sjs_js_Object(properties);
    var this$7 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(body);
    if ((!this$7.isEmpty__Z())) {
      var x0 = this$7.get__O();
      var b = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_T(x0);
      init.body = b;
    }
    var p = fetch(url, init);
    var f = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sjs_js_Thenable$ThenableOps$().toFuture$extension__sjs_js_Thenable__s_concurrent_Future(p)).flatMap__F1__s_concurrent_ExecutionContext__s_concurrent_Future(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((response) => {
      var p$1 = response.text();
      return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sjs_js_Thenable$ThenableOps$().toFuture$extension__sjs_js_Thenable__s_concurrent_Future(p$1)).map__F1__s_concurrent_ExecutionContext__s_concurrent_Future(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((text) => {
        var text$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_T(text);
        if ($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$uZ(response.ok)) {
          return text$1;
        } else {
          throw $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$ct_jl_Exception__T__(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_jl_Exception(), ((("HTTP " + $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$uI(response.status)) + ": ") + text$1));
        }
      })), $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_s_concurrent_ExecutionContext$().global__s_concurrent_ExecutionContextExecutor());
    })), $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_s_concurrent_ExecutionContext$().global__s_concurrent_ExecutionContextExecutor());
    return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sjs_js_JSConverters$JSRichFuture$().toJSPromise$extension__s_concurrent_Future__s_concurrent_ExecutionContext__sjs_js_Promise(f, $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_s_concurrent_ExecutionContext$().global__s_concurrent_ExecutionContextExecutor());
  }));
  return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lzio_ZIOCompanionPlatformSpecific__fromPromiseJS__F0__O__Lzio_ZIO(this$18, promise, "quasar.frontend.BrowserHttpClient.fetch(BrowserHttpClient.scala:40)");
}
export { $p_Lquasar_frontend_BrowserHttpClient__fetch__T__T__s_Option__Lzio_ZIO as $p_Lquasar_frontend_BrowserHttpClient__fetch__T__T__s_Option__Lzio_ZIO };
/** @constructor */
function $c_Lquasar_frontend_BrowserHttpClient(baseUrl) {
  this.Lquasar_frontend_BrowserHttpClient__f_baseUrl = null;
  this.Lquasar_frontend_BrowserHttpClient__f_baseUrl = baseUrl;
}
export { $c_Lquasar_frontend_BrowserHttpClient as $c_Lquasar_frontend_BrowserHttpClient };
$c_Lquasar_frontend_BrowserHttpClient.prototype = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$h_O();
$c_Lquasar_frontend_BrowserHttpClient.prototype.constructor = $c_Lquasar_frontend_BrowserHttpClient;
/** @constructor */
function $h_Lquasar_frontend_BrowserHttpClient() {
}
export { $h_Lquasar_frontend_BrowserHttpClient as $h_Lquasar_frontend_BrowserHttpClient };
$h_Lquasar_frontend_BrowserHttpClient.prototype = $c_Lquasar_frontend_BrowserHttpClient.prototype;
$c_Lquasar_frontend_BrowserHttpClient.prototype.productIterator__sc_Iterator = (function() {
  return new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_Product$$anon$1(this);
});
$c_Lquasar_frontend_BrowserHttpClient.prototype.hashCode__I = (function() {
  return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_s_util_hashing_MurmurHash3$().productHash__s_Product__I__Z__I(this, 157811732, true);
});
$c_Lquasar_frontend_BrowserHttpClient.prototype.equals__O__Z = (function(x$0) {
  if ((this === x$0)) {
    return true;
  } else if ((x$0 instanceof $c_Lquasar_frontend_BrowserHttpClient)) {
    var x2 = $as_Lquasar_frontend_BrowserHttpClient(x$0);
    return (this.Lquasar_frontend_BrowserHttpClient__f_baseUrl === $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x2).Lquasar_frontend_BrowserHttpClient__f_baseUrl);
  } else {
    return false;
  }
});
$c_Lquasar_frontend_BrowserHttpClient.prototype.toString__T = (function() {
  return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$()._toString__s_Product__T(this);
});
$c_Lquasar_frontend_BrowserHttpClient.prototype.productArity__I = (function() {
  return 1;
});
$c_Lquasar_frontend_BrowserHttpClient.prototype.productPrefix__T = (function() {
  return "BrowserHttpClient";
});
$c_Lquasar_frontend_BrowserHttpClient.prototype.productElement__I__O = (function(n) {
  if ((n === 0)) {
    return this.Lquasar_frontend_BrowserHttpClient__f_baseUrl;
  }
  throw $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$ct_jl_IndexOutOfBoundsException__T__(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_jl_IndexOutOfBoundsException(), ("" + n));
});
$c_Lquasar_frontend_BrowserHttpClient.prototype.post__T__T__Lzio_ZIO = (function(path, body) {
  return $p_Lquasar_frontend_BrowserHttpClient__fetch__T__T__s_Option__Lzio_ZIO(this, path, "POST", new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_Some(body));
});
function $as_Lquasar_frontend_BrowserHttpClient(obj) {
  return (((obj instanceof $c_Lquasar_frontend_BrowserHttpClient) || (obj === null)) ? obj : $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$throwClassCastException(obj, "quasar.frontend.BrowserHttpClient"));
}
export { $as_Lquasar_frontend_BrowserHttpClient as $as_Lquasar_frontend_BrowserHttpClient };
function $isArrayOf_Lquasar_frontend_BrowserHttpClient(obj, depth) {
  return (!(!(((obj && obj.$classData) && (obj.$classData.arrayDepth === depth)) && obj.$classData.arrayBase.ancestors.Lquasar_frontend_BrowserHttpClient)));
}
export { $isArrayOf_Lquasar_frontend_BrowserHttpClient as $isArrayOf_Lquasar_frontend_BrowserHttpClient };
function $asArrayOf_Lquasar_frontend_BrowserHttpClient(obj, depth) {
  return (($isArrayOf_Lquasar_frontend_BrowserHttpClient(obj, depth) || (obj === null)) ? obj : $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$throwArrayCastException(obj, "Lquasar.frontend.BrowserHttpClient;", depth));
}
export { $asArrayOf_Lquasar_frontend_BrowserHttpClient as $asArrayOf_Lquasar_frontend_BrowserHttpClient };
var $d_Lquasar_frontend_BrowserHttpClient = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$TypeData().initClass($c_Lquasar_frontend_BrowserHttpClient, "quasar.frontend.BrowserHttpClient", ({
  Lquasar_frontend_BrowserHttpClient: 1,
  s_Equals: 1,
  s_Product: 1,
  Ljava_io_Serializable: 1
}));
export { $d_Lquasar_frontend_BrowserHttpClient as $d_Lquasar_frontend_BrowserHttpClient };
//# sourceMappingURL=quasar.frontend.-Browser-Http-Client.js.map

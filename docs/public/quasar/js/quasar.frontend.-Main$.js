'use strict';
import * as $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6 from "./internal-3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.js";
import * as $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024 from "./quasar.frontend.-Quasar-App$.js";
function $p_Lquasar_frontend_Main$__mount$1__T__T__V($thiz, baseUrl$1, docsBase$1) {
  var container = document.getElementById("quasar-app");
  if ((container !== null)) {
    $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
    var rootNode = $j_quasar$002efrontend$002e$002dQuasar$002dApp$0024.$m_Lquasar_frontend_QuasarApp$().apply__T__T__Lcom_raquo_laminar_nodes_ReactiveHtmlElement(baseUrl$1, docsBase$1);
    new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_laminar_nodes_RootNode(container, rootNode);
  } else {
    console.warn("Quasar demo container not found");
  }
}
export { $p_Lquasar_frontend_Main$__mount$1__T__T__V as $p_Lquasar_frontend_Main$__mount$1__T__T__V };
/** @constructor */
function $c_Lquasar_frontend_Main$() {
}
export { $c_Lquasar_frontend_Main$ as $c_Lquasar_frontend_Main$ };
$c_Lquasar_frontend_Main$.prototype = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$h_O();
$c_Lquasar_frontend_Main$.prototype.constructor = $c_Lquasar_frontend_Main$;
/** @constructor */
function $h_Lquasar_frontend_Main$() {
}
export { $h_Lquasar_frontend_Main$ as $h_Lquasar_frontend_Main$ };
$h_Lquasar_frontend_Main$.prototype = $c_Lquasar_frontend_Main$.prototype;
$c_Lquasar_frontend_Main$.prototype.main__AT__V = (function(args) {
  var metaTag = document.querySelector("meta[name=quasar-api-url]");
  var baseUrl = ((metaTag !== null) ? $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_T(metaTag.content) : "http://localhost:8080");
  var docsBaseDynamic = window.__GRAVITON_DOCS_BASE__;
  var docsBase = (((docsBaseDynamic === (void 0)) || (docsBaseDynamic === null)) ? "" : $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$dp_toString__T($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(docsBaseDynamic)));
  if (($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_T(document.readyState) === "loading")) {
    window.addEventListener("DOMContentLoaded", ((_$1) => {
      $p_Lquasar_frontend_Main$__mount$1__T__T__V(this, baseUrl, docsBase);
    }));
  } else {
    $p_Lquasar_frontend_Main$__mount$1__T__T__V(this, baseUrl, docsBase);
  }
});
var $d_Lquasar_frontend_Main$ = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$TypeData().initClass($c_Lquasar_frontend_Main$, "quasar.frontend.Main$", ({
  Lquasar_frontend_Main$: 1
}));
export { $d_Lquasar_frontend_Main$ as $d_Lquasar_frontend_Main$ };
var $n_Lquasar_frontend_Main$;
function $m_Lquasar_frontend_Main$() {
  if ((!$n_Lquasar_frontend_Main$)) {
    $n_Lquasar_frontend_Main$ = new $c_Lquasar_frontend_Main$();
  }
  return $n_Lquasar_frontend_Main$;
}
export { $m_Lquasar_frontend_Main$ as $m_Lquasar_frontend_Main$ };
//# sourceMappingURL=quasar.frontend.-Main$.js.map

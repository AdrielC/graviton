'use strict';
import * as $j_graviton$002efrontend$002e$002dGraviton$002dApp$0024 from "./graviton.frontend.-Graviton-App$.js";
import * as $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6 from "./internal-3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.js";
import * as $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c from "./internal-9eb04c7a932b923c3bc5eb25fd6219ee930b689c.js";
/** @constructor */
function $c_Lgraviton_frontend_Main$() {
}
export { $c_Lgraviton_frontend_Main$ as $c_Lgraviton_frontend_Main$ };
$c_Lgraviton_frontend_Main$.prototype = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$h_O();
$c_Lgraviton_frontend_Main$.prototype.constructor = $c_Lgraviton_frontend_Main$;
/** @constructor */
function $h_Lgraviton_frontend_Main$() {
}
export { $h_Lgraviton_frontend_Main$ as $h_Lgraviton_frontend_Main$ };
$h_Lgraviton_frontend_Main$.prototype = $c_Lgraviton_frontend_Main$.prototype;
$c_Lgraviton_frontend_Main$.prototype.main__AT__V = (function(args) {
  var metaTag = document.querySelector("meta[name=graviton-api-url]");
  var baseUrl = ((metaTag !== null) ? $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$as_T(metaTag.content) : "http://localhost:8080");
  var this$1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_api_package$().Lcom_raquo_laminar_api_package$__f_L);
  var container = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => document.getElementById("graviton-app")));
  var rootNode = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => $j_graviton$002efrontend$002e$002dGraviton$002dApp$0024.$m_Lgraviton_frontend_GravitonApp$().apply__T__Lcom_raquo_laminar_nodes_ReactiveHtmlElement(baseUrl)));
  var x0 = this$1.Lcom_raquo_laminar_api_package$$anon$1__f_documentEventProps;
  var eventProp = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(x0).onDomContentLoaded__Lcom_raquo_laminar_keys_EventProp();
  var p = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_laminar_keys_EventProcessor$().empty__Lcom_raquo_laminar_keys_EventProp__Z__Z__Lcom_raquo_laminar_keys_EventProcessor(eventProp, false, false);
  var this$5 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lcom_raquo_airstream_web_DomEventStream$().apply__Lorg_scalajs_dom_EventTarget__T__Z__Lcom_raquo_airstream_core_EventStream(document, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(p).Lcom_raquo_laminar_keys_EventProcessor__f_eventProp).Lcom_raquo_laminar_keys_EventProp__f_name, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(p).Lcom_raquo_laminar_keys_EventProcessor__f_shouldUseCapture));
  var fn = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(p).Lcom_raquo_laminar_keys_EventProcessor__f_processor;
  var this$6 = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_airstream_misc_CollectStream(this$5, fn);
  var onNext = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sjsr_AnonFunction1_$$Lambda$3aa60c34ef08a878abffbf4628007cc68fa3c7ab(((_$2) => {
    new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_Lcom_raquo_laminar_nodes_RootNode(container.apply__O(), $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_Lcom_raquo_laminar_nodes_ReactiveElement(rootNode.apply__O()));
  }));
  var owner = this$1.unsafeWindowOwner__Lcom_raquo_laminar_api_Laminar$unsafeWindowOwner$();
  $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$f_Lcom_raquo_airstream_core_BaseObservable__foreach__F1__Lcom_raquo_airstream_ownership_Owner__Lcom_raquo_airstream_ownership_Subscription(this$6, onNext, owner);
});
var $d_Lgraviton_frontend_Main$ = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$TypeData().initClass($c_Lgraviton_frontend_Main$, "graviton.frontend.Main$", ({
  Lgraviton_frontend_Main$: 1
}));
export { $d_Lgraviton_frontend_Main$ as $d_Lgraviton_frontend_Main$ };
var $n_Lgraviton_frontend_Main$;
function $m_Lgraviton_frontend_Main$() {
  if ((!$n_Lgraviton_frontend_Main$)) {
    $n_Lgraviton_frontend_Main$ = new $c_Lgraviton_frontend_Main$();
  }
  return $n_Lgraviton_frontend_Main$;
}
export { $m_Lgraviton_frontend_Main$ as $m_Lgraviton_frontend_Main$ };
//# sourceMappingURL=graviton.frontend.-Main$.js.map

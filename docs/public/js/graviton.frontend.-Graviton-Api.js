'use strict';
import * as $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6 from "./internal-3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.js";
import * as $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c from "./internal-9eb04c7a932b923c3bc5eb25fd6219ee930b689c.js";
/** @constructor */
function $c_Lgraviton_frontend_GravitonApi(client) {
  this.Lgraviton_frontend_GravitonApi__f_client = null;
  this.Lgraviton_frontend_GravitonApi__f_client = client;
}
export { $c_Lgraviton_frontend_GravitonApi as $c_Lgraviton_frontend_GravitonApi };
$c_Lgraviton_frontend_GravitonApi.prototype = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$h_O();
$c_Lgraviton_frontend_GravitonApi.prototype.constructor = $c_Lgraviton_frontend_GravitonApi;
/** @constructor */
function $h_Lgraviton_frontend_GravitonApi() {
}
export { $h_Lgraviton_frontend_GravitonApi as $h_Lgraviton_frontend_GravitonApi };
$h_Lgraviton_frontend_GravitonApi.prototype = $c_Lgraviton_frontend_GravitonApi.prototype;
$c_Lgraviton_frontend_GravitonApi.prototype.getHealth__Lzio_ZIO = (function() {
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZIO$();
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZIO$();
  var eval$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => this.Lgraviton_frontend_GravitonApi__f_client));
  var this$9 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_ZIO$Sync("graviton.frontend.GravitonApi.getHealth(GravitonApi.scala:11)", eval$1);
  var k = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((c) => {
    var c$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_Lgraviton_shared_HttpClient(c);
    var $x_1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lgraviton_shared_HttpClient$();
    $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_json_JsonDecoder$();
    var codec = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lgraviton_shared_ApiModels$HealthResponse$().derived$JsonCodec__Lzio_json_JsonCodec();
    var this$4 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($x_1.getJson__T__Lzio_json_JsonDecoder__Lzio_ZIO("/api/health", $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(codec).Lzio_json_JsonCodec__f_decoder));
    var $x_2 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_FiberRef$().Lzio_FiberRef$__f_currentEnvironment);
    var this$7 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZEnvironment$();
    var this$6 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_package$Tag$();
    var tag0 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lizumi_reflect_macrortti_LightTypeTag$().parse__I__T__T__I__Lizumi_reflect_macrortti_LightTypeTag(688239083, "\u0004\u0000\u0001\u001agraviton.shared.HttpClient\u0001\u0001", "\u0000\u0000\u0000", 30);
    var tag0$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lizumi_reflect_Tag$$anon$1($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lgraviton_shared_HttpClient.getClassOf(), tag0);
    var evidence$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_package$Tag$$anon$1(tag0$1, this$6);
    var this$8 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(this$7.Lzio_ZEnvironment$__f_empty);
    return $x_2.locally__O__Lzio_ZIO__O__Lzio_ZIO($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(this$8.Lzio_ZEnvironment__f_unsafe).add__Lizumi_reflect_macrortti_LightTypeTag__O__Lzio_Unsafe__Lzio_ZEnvironment(evidence$1.tag__Lizumi_reflect_macrortti_LightTypeTag(), c$1, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_Unsafe$().Lzio_Unsafe$__f_unsafe), this$4, "graviton.frontend.GravitonApi.getHealth(GravitonApi.scala:12)");
  }));
  return new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_ZIO$FlatMap("graviton.frontend.GravitonApi.getHealth(GravitonApi.scala:13)", this$9, k);
});
$c_Lgraviton_frontend_GravitonApi.prototype.getStats__Lzio_ZIO = (function() {
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZIO$();
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZIO$();
  var eval$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => this.Lgraviton_frontend_GravitonApi__f_client));
  var this$9 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_ZIO$Sync("graviton.frontend.GravitonApi.getStats(GravitonApi.scala:16)", eval$1);
  var k = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((c) => {
    var c$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_Lgraviton_shared_HttpClient(c);
    var $x_1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lgraviton_shared_HttpClient$();
    $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_json_JsonDecoder$();
    var codec = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lgraviton_shared_ApiModels$SystemStats$().derived$JsonCodec__Lzio_json_JsonCodec();
    var this$4 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($x_1.getJson__T__Lzio_json_JsonDecoder__Lzio_ZIO("/api/stats", $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(codec).Lzio_json_JsonCodec__f_decoder));
    var $x_2 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_FiberRef$().Lzio_FiberRef$__f_currentEnvironment);
    var this$7 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZEnvironment$();
    var this$6 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_package$Tag$();
    var tag0 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lizumi_reflect_macrortti_LightTypeTag$().parse__I__T__T__I__Lizumi_reflect_macrortti_LightTypeTag(688239083, "\u0004\u0000\u0001\u001agraviton.shared.HttpClient\u0001\u0001", "\u0000\u0000\u0000", 30);
    var tag0$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lizumi_reflect_Tag$$anon$1($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lgraviton_shared_HttpClient.getClassOf(), tag0);
    var evidence$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_package$Tag$$anon$1(tag0$1, this$6);
    var this$8 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(this$7.Lzio_ZEnvironment$__f_empty);
    return $x_2.locally__O__Lzio_ZIO__O__Lzio_ZIO($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(this$8.Lzio_ZEnvironment__f_unsafe).add__Lizumi_reflect_macrortti_LightTypeTag__O__Lzio_Unsafe__Lzio_ZEnvironment(evidence$1.tag__Lizumi_reflect_macrortti_LightTypeTag(), c$1, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_Unsafe$().Lzio_Unsafe$__f_unsafe), this$4, "graviton.frontend.GravitonApi.getStats(GravitonApi.scala:17)");
  }));
  return new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_ZIO$FlatMap("graviton.frontend.GravitonApi.getStats(GravitonApi.scala:18)", this$9, k);
});
$c_Lgraviton_frontend_GravitonApi.prototype.getBlobMetadata__Lgraviton_shared_ApiModels$BlobId__Lzio_ZIO = (function(blobId) {
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZIO$();
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZIO$();
  var eval$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => this.Lgraviton_frontend_GravitonApi__f_client));
  var this$9 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_ZIO$Sync("graviton.frontend.GravitonApi.getBlobMetadata(GravitonApi.scala:21)", eval$1);
  var k = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((c) => {
    var c$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_Lgraviton_shared_HttpClient(c);
    var $x_2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lgraviton_shared_HttpClient$();
    var $x_1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(blobId).Lgraviton_shared_ApiModels$BlobId__f_value;
    $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_json_JsonDecoder$();
    var codec = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lgraviton_shared_ApiModels$BlobMetadata$().derived$JsonCodec__Lzio_json_JsonCodec();
    var this$4 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($x_2.getJson__T__Lzio_json_JsonDecoder__Lzio_ZIO(("/api/blobs/" + $x_1), $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(codec).Lzio_json_JsonCodec__f_decoder));
    var $x_3 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_FiberRef$().Lzio_FiberRef$__f_currentEnvironment);
    var this$7 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZEnvironment$();
    var this$6 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_package$Tag$();
    var tag0 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lizumi_reflect_macrortti_LightTypeTag$().parse__I__T__T__I__Lizumi_reflect_macrortti_LightTypeTag(688239083, "\u0004\u0000\u0001\u001agraviton.shared.HttpClient\u0001\u0001", "\u0000\u0000\u0000", 30);
    var tag0$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lizumi_reflect_Tag$$anon$1($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lgraviton_shared_HttpClient.getClassOf(), tag0);
    var evidence$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_package$Tag$$anon$1(tag0$1, this$6);
    var this$8 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(this$7.Lzio_ZEnvironment$__f_empty);
    return $x_3.locally__O__Lzio_ZIO__O__Lzio_ZIO($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(this$8.Lzio_ZEnvironment__f_unsafe).add__Lizumi_reflect_macrortti_LightTypeTag__O__Lzio_Unsafe__Lzio_ZEnvironment(evidence$1.tag__Lizumi_reflect_macrortti_LightTypeTag(), c$1, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_Unsafe$().Lzio_Unsafe$__f_unsafe), this$4, "graviton.frontend.GravitonApi.getBlobMetadata(GravitonApi.scala:22)");
  }));
  return new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_ZIO$FlatMap("graviton.frontend.GravitonApi.getBlobMetadata(GravitonApi.scala:23)", this$9, k);
});
$c_Lgraviton_frontend_GravitonApi.prototype.getBlobManifest__Lgraviton_shared_ApiModels$BlobId__Lzio_ZIO = (function(blobId) {
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZIO$();
  $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZIO$();
  var eval$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction0_$$Lambda$a02b774b97db8234e08c6a02dd06557c99779855((() => this.Lgraviton_frontend_GravitonApi__f_client));
  var this$9 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_ZIO$Sync("graviton.frontend.GravitonApi.getBlobManifest(GravitonApi.scala:26)", eval$1);
  var k = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_sr_AbstractFunction1_$$Lambda$70e1780b84463d18653aacefee3ab989ac625f28(((c) => {
    var c$1 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$as_Lgraviton_shared_HttpClient(c);
    var $x_2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lgraviton_shared_HttpClient$();
    var $x_1 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(blobId).Lgraviton_shared_ApiModels$BlobId__f_value;
    $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lzio_json_JsonDecoder$();
    var codec = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_Lgraviton_shared_ApiModels$BlobManifest$().derived$JsonCodec__Lzio_json_JsonCodec();
    var this$4 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($x_2.getJson__T__Lzio_json_JsonDecoder__Lzio_ZIO((("/api/blobs/" + $x_1) + "/manifest"), $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(codec).Lzio_json_JsonCodec__f_decoder));
    var $x_3 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_FiberRef$().Lzio_FiberRef$__f_currentEnvironment);
    var this$7 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_ZEnvironment$();
    var this$6 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_package$Tag$();
    var tag0 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lizumi_reflect_macrortti_LightTypeTag$().parse__I__T__T__I__Lizumi_reflect_macrortti_LightTypeTag(688239083, "\u0004\u0000\u0001\u001agraviton.shared.HttpClient\u0001\u0001", "\u0000\u0000\u0000", 30);
    var tag0$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lizumi_reflect_Tag$$anon$1($j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$d_Lgraviton_shared_HttpClient.getClassOf(), tag0);
    var evidence$1 = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_package$Tag$$anon$1(tag0$1, this$6);
    var this$8 = $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(this$7.Lzio_ZEnvironment$__f_empty);
    return $x_3.locally__O__Lzio_ZIO__O__Lzio_ZIO($j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$n(this$8.Lzio_ZEnvironment__f_unsafe).add__Lizumi_reflect_macrortti_LightTypeTag__O__Lzio_Unsafe__Lzio_ZEnvironment(evidence$1.tag__Lizumi_reflect_macrortti_LightTypeTag(), c$1, $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$m_Lzio_Unsafe$().Lzio_Unsafe$__f_unsafe), this$4, "graviton.frontend.GravitonApi.getBlobManifest(GravitonApi.scala:27)");
  }));
  return new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$c_Lzio_ZIO$FlatMap("graviton.frontend.GravitonApi.getBlobManifest(GravitonApi.scala:28)", this$9, k);
});
var $d_Lgraviton_frontend_GravitonApi = new $j_internal$002d9eb04c7a932b923c3bc5eb25fd6219ee930b689c.$TypeData().initClass($c_Lgraviton_frontend_GravitonApi, "graviton.frontend.GravitonApi", ({
  Lgraviton_frontend_GravitonApi: 1
}));
export { $d_Lgraviton_frontend_GravitonApi as $d_Lgraviton_frontend_GravitonApi };
//# sourceMappingURL=graviton.frontend.-Graviton-Api.js.map

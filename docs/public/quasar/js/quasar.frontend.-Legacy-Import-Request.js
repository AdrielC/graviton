'use strict';
import * as $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6 from "./internal-3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.js";
/** @constructor */
function $c_Lquasar_frontend_LegacyImportRequest(legacyRepo, legacyDocId, mode) {
  this.Lquasar_frontend_LegacyImportRequest__f_legacyRepo = null;
  this.Lquasar_frontend_LegacyImportRequest__f_legacyDocId = null;
  this.Lquasar_frontend_LegacyImportRequest__f_mode = null;
  this.Lquasar_frontend_LegacyImportRequest__f_legacyRepo = legacyRepo;
  this.Lquasar_frontend_LegacyImportRequest__f_legacyDocId = legacyDocId;
  this.Lquasar_frontend_LegacyImportRequest__f_mode = mode;
}
export { $c_Lquasar_frontend_LegacyImportRequest as $c_Lquasar_frontend_LegacyImportRequest };
$c_Lquasar_frontend_LegacyImportRequest.prototype = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$h_O();
$c_Lquasar_frontend_LegacyImportRequest.prototype.constructor = $c_Lquasar_frontend_LegacyImportRequest;
/** @constructor */
function $h_Lquasar_frontend_LegacyImportRequest() {
}
export { $h_Lquasar_frontend_LegacyImportRequest as $h_Lquasar_frontend_LegacyImportRequest };
$h_Lquasar_frontend_LegacyImportRequest.prototype = $c_Lquasar_frontend_LegacyImportRequest.prototype;
$c_Lquasar_frontend_LegacyImportRequest.prototype.productIterator__sc_Iterator = (function() {
  return new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_s_Product$$anon$1(this);
});
$c_Lquasar_frontend_LegacyImportRequest.prototype.hashCode__I = (function() {
  return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_s_util_hashing_MurmurHash3$().productHash__s_Product__I__Z__I(this, 824972061, true);
});
$c_Lquasar_frontend_LegacyImportRequest.prototype.equals__O__Z = (function(x$0) {
  if ((this === x$0)) {
    return true;
  } else if ((x$0 instanceof $c_Lquasar_frontend_LegacyImportRequest)) {
    var x2 = $as_Lquasar_frontend_LegacyImportRequest(x$0);
    if (((this.Lquasar_frontend_LegacyImportRequest__f_legacyRepo === $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x2).Lquasar_frontend_LegacyImportRequest__f_legacyRepo) && (this.Lquasar_frontend_LegacyImportRequest__f_legacyDocId === $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x2).Lquasar_frontend_LegacyImportRequest__f_legacyDocId))) {
      var x = this.Lquasar_frontend_LegacyImportRequest__f_mode;
      var x$2 = $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x2).Lquasar_frontend_LegacyImportRequest__f_mode;
      return ((x === null) ? (x$2 === null) : $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$n(x).equals__O__Z(x$2));
    } else {
      return false;
    }
  } else {
    return false;
  }
});
$c_Lquasar_frontend_LegacyImportRequest.prototype.toString__T = (function() {
  return $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$m_sr_ScalaRunTime$()._toString__s_Product__T(this);
});
$c_Lquasar_frontend_LegacyImportRequest.prototype.productArity__I = (function() {
  return 3;
});
$c_Lquasar_frontend_LegacyImportRequest.prototype.productPrefix__T = (function() {
  return "LegacyImportRequest";
});
$c_Lquasar_frontend_LegacyImportRequest.prototype.productElement__I__O = (function(n) {
  switch (n) {
    case 0: {
      return this.Lquasar_frontend_LegacyImportRequest__f_legacyRepo;
      break;
    }
    case 1: {
      return this.Lquasar_frontend_LegacyImportRequest__f_legacyDocId;
      break;
    }
    case 2: {
      return this.Lquasar_frontend_LegacyImportRequest__f_mode;
      break;
    }
    default: {
      throw $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$ct_jl_IndexOutOfBoundsException__T__(new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$c_jl_IndexOutOfBoundsException(), ("" + n));
    }
  }
});
function $as_Lquasar_frontend_LegacyImportRequest(obj) {
  return (((obj instanceof $c_Lquasar_frontend_LegacyImportRequest) || (obj === null)) ? obj : $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$throwClassCastException(obj, "quasar.frontend.LegacyImportRequest"));
}
export { $as_Lquasar_frontend_LegacyImportRequest as $as_Lquasar_frontend_LegacyImportRequest };
function $isArrayOf_Lquasar_frontend_LegacyImportRequest(obj, depth) {
  return (!(!(((obj && obj.$classData) && (obj.$classData.arrayDepth === depth)) && obj.$classData.arrayBase.ancestors.Lquasar_frontend_LegacyImportRequest)));
}
export { $isArrayOf_Lquasar_frontend_LegacyImportRequest as $isArrayOf_Lquasar_frontend_LegacyImportRequest };
function $asArrayOf_Lquasar_frontend_LegacyImportRequest(obj, depth) {
  return (($isArrayOf_Lquasar_frontend_LegacyImportRequest(obj, depth) || (obj === null)) ? obj : $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$throwArrayCastException(obj, "Lquasar.frontend.LegacyImportRequest;", depth));
}
export { $asArrayOf_Lquasar_frontend_LegacyImportRequest as $asArrayOf_Lquasar_frontend_LegacyImportRequest };
var $d_Lquasar_frontend_LegacyImportRequest = new $j_internal$002d3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.$TypeData().initClass($c_Lquasar_frontend_LegacyImportRequest, "quasar.frontend.LegacyImportRequest", ({
  Lquasar_frontend_LegacyImportRequest: 1,
  s_Equals: 1,
  s_Product: 1,
  Ljava_io_Serializable: 1
}));
export { $d_Lquasar_frontend_LegacyImportRequest as $d_Lquasar_frontend_LegacyImportRequest };
//# sourceMappingURL=quasar.frontend.-Legacy-Import-Request.js.map

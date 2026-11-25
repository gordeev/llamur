#pragma once

namespace motoai {
// v0.9.0 moved this internal invariant from hparams to the model accessor.
// Keep historical baseline sources buildable while checking the actual state.
template<typename Model>
auto model_no_alloc(const Model * model, int) -> decltype(model->no_alloc()) {
    return model->no_alloc();
}
template<typename Model>
auto model_no_alloc(const Model * model, long) -> decltype(model->hparams.no_alloc) {
    return model->hparams.no_alloc;
}
}

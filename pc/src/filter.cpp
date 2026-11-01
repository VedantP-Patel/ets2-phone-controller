#include "filter.h"
#include <algorithm>

namespace {
    constexpr float PI_F = 3.14159265358979323846f;
}

OneEuroFilter::OneEuroFilter(float freq, float mincutoff, float beta, float dcutoff)
    : freq_(freq),
      mincutoff_(mincutoff),
      beta_(beta),
      dcutoff_(dcutoff),
      xFilter_(computeAlpha(mincutoff)),
      dxFilter_(computeAlpha(dcutoff)),
      lastTime_(0.0),
      hasLastTime_(false) {}

float OneEuroFilter::computeAlpha(float cutoff) const {
    float te = 1.0f / freq_;
    float tau = 1.0f / (2.0f * PI_F * cutoff);
    return 1.0f / (1.0f + tau / te);
}

float OneEuroFilter::filter(float x, double timestamp) {
    if (hasLastTime_) {
        double elapsed = timestamp - lastTime_;
        if (elapsed > 0.0001) {
            freq_ = static_cast<float>(1.0 / elapsed);
        }
    }
    lastTime_ = timestamp;
    hasLastTime_ = true;

    // Estimate the derivative (speed of change)
    float dx = xFilter_.hasValue() ? (x - xFilter_.last()) * freq_ : 0.0f;
    float edx = dxFilter_.filter(dx, computeAlpha(dcutoff_));

    // Calculate dynamic cutoff frequency based on movement velocity
    float cutoff = mincutoff_ + beta_ * std::abs(edx);
    return xFilter_.filter(x, computeAlpha(cutoff));
}

void OneEuroFilter::reset() {
    xFilter_.reset();
    dxFilter_.reset();
    hasLastTime_ = false;
    lastTime_ = 0.0;
}


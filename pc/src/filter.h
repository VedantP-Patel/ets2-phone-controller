#pragma once

#include <cmath>

class LowPassFilter {
public:
    explicit LowPassFilter(float alpha = 1.0f) 
        : alpha_(alpha), s_(0.0f), initialized_(false) {}

    float filter(float value, float alpha) {
        alpha_ = alpha;
        if (!initialized_) {
            s_ = value;
            initialized_ = true;
        } else {
            s_ = alpha_ * value + (1.0f - alpha_) * s_;
        }
        return s_;
    }

    float last() const { return s_; }
    bool hasValue() const { return initialized_; }
    void reset() { initialized_ = false; s_ = 0.0f; }

private:
    float alpha_;
    float s_;
    bool initialized_;
};

class OneEuroFilter {
public:
    OneEuroFilter(float freq, float mincutoff = 1.0f, float beta = 0.007f, float dcutoff = 1.0f);

    float filter(float x, double timestamp);
    void reset();

private:
    float computeAlpha(float cutoff) const;

    float freq_;
    float mincutoff_;
    float beta_;
    float dcutoff_;
    LowPassFilter xFilter_;
    LowPassFilter dxFilter_;
    double lastTime_;
    bool hasLastTime_;
};


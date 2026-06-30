# -*- coding: utf-8 -*-
"""
test_hud_smooth.py - HUDのフレーム補間ロジックのテスト

TDDフロー：
  Red   → このテストが最初は失敗する（lerp_frame_value が存在しない）
  Green → hud_encoder.py に実装して通す
  Refactor
"""

import sys
import os
import pytest

# hud_encoder.py をパスに追加
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

# ─────────────────────────────────────────────────────────────
# 補間ユーティリティのテスト
# ─────────────────────────────────────────────────────────────

from hud_encoder import lerp_frame_value, frames_between


class TestLerpFrameValue:
    """lerp_frame_value(v0, v1, alpha) の動作確認"""

    def test_alpha_zero_returns_start(self):
        assert lerp_frame_value(100.0, 200.0, 0.0) == pytest.approx(100.0)

    def test_alpha_one_returns_end(self):
        assert lerp_frame_value(100.0, 200.0, 1.0) == pytest.approx(200.0)

    def test_alpha_half_returns_midpoint(self):
        assert lerp_frame_value(100.0, 200.0, 0.5) == pytest.approx(150.0)

    def test_integer_midpoint_rounded(self):
        """整数値指標（power, cadence, hr）はround()で返ること"""
        result = lerp_frame_value(100, 101, 0.4, as_int=True)
        assert isinstance(result, int)
        assert result == 100  # 100 + 0.4*1 = 100.4 → 100

    def test_integer_rounds_to_nearest(self):
        result = lerp_frame_value(100, 101, 0.6, as_int=True)
        assert result == 101  # 100.6 → 101

    def test_decreasing_values(self):
        """値が下がる場合も正しく補間"""
        assert lerp_frame_value(200.0, 100.0, 0.25) == pytest.approx(175.0)

    def test_clamp_grade_negative(self):
        """負の勾配も補間できること"""
        assert lerp_frame_value(-5.0, -10.0, 0.5) == pytest.approx(-7.5)


class TestFramesBetween:
    """frames_between(t0, t1, fps) の動作確認"""

    def test_one_second_30fps(self):
        frames = list(frames_between(0.0, 1.0, 30.0))
        # t=0.0, 1/30, 2/30, ..., 29/30 の30フレーム
        assert len(frames) == 30

    def test_one_second_60fps(self):
        frames = list(frames_between(0.0, 1.0, 60.0))
        assert len(frames) == 60

    def test_frame_times_increase(self):
        frames = list(frames_between(5.0, 6.0, 30.0))
        times = [t for t, _ in frames]
        assert all(times[i] < times[i + 1] for i in range(len(times) - 1))

    def test_alpha_starts_at_zero(self):
        frames = list(frames_between(0.0, 1.0, 30.0))
        _, first_alpha = frames[0]
        assert first_alpha == pytest.approx(0.0)

    def test_alpha_never_reaches_one(self):
        """最後のフレームはちょうど t1 に届かない（次セグメントと重複しないため）"""
        frames = list(frames_between(0.0, 1.0, 30.0))
        _, last_alpha = frames[-1]
        assert last_alpha < 1.0

    def test_partial_second_30fps(self):
        """0.5秒のセグメントは15フレーム"""
        frames = list(frames_between(2.0, 2.5, 30.0))
        assert len(frames) == 15

    def test_realworld_gap_exceeds_one_second(self):
        """FITデータで稀に2秒以上の間隔がある場合にも正しくフレーム数を返す"""
        frames = list(frames_between(0.0, 2.0, 30.0))
        assert len(frames) == 60


class TestSmoothnessIntegration:
    """生成されるASSイベントの連続性確認"""

    def test_no_value_jumps_between_segments(self):
        """
        セグメント境界でのジャンプがないこと：
        segment[i]の最終フレームの値とsegment[i+1]の最初のフレームの値が
        連続していること（厳密一致は不要、差が1未満）
        """
        records = [
            {'t': 0.0, 'spd': 10.0, 'pwr': 100, 'cad': 80, 'hr': 130,
             'alt': 100.0, 'grd': 1.0, 'dst': 0.0},
            {'t': 1.0, 'spd': 20.0, 'pwr': 200, 'cad': 90, 'hr': 150,
             'alt': 110.0, 'grd': 2.0, 'dst': 0.01},
            {'t': 2.0, 'spd': 15.0, 'pwr': 150, 'cad': 85, 'hr': 140,
             'alt': 105.0, 'grd': 1.5, 'dst': 0.02},
        ]

        fps = 30.0

        # segment 0→1 の最終フレームのspd
        seg0_frames = list(frames_between(0.0, 1.0, fps))
        last_alpha = seg0_frames[-1][1]
        seg0_last_spd = lerp_frame_value(10.0, 20.0, last_alpha)

        # segment 1→2 の最初のフレームのspd
        seg1_frames = list(frames_between(1.0, 2.0, fps))
        first_alpha = seg1_frames[0][1]
        seg1_first_spd = lerp_frame_value(20.0, 15.0, first_alpha)

        # seg0の末尾は ~20.0 に近く、seg1の先頭は 20.0 (alpha=0)
        assert abs(seg0_last_spd - 20.0) < 1.0  # ほぼ20
        assert seg1_first_spd == pytest.approx(20.0)  # 正確に20

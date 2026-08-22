using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using DynamicDriving.Core.Audio;

namespace DynamicDriving.Editor.Views;

/// <summary>
/// Draws the transport stem's waveform with the loop region over it, and lets the user click to
/// seek or drag either loop handle.
///
/// Drawing is done directly in <see cref="OnRender"/> rather than through a shaped item list: a
/// waveform is a thousand thin bars that change together, and redrawing them as one pass keeps the
/// playhead smooth while audio is running.
/// </summary>
public sealed class WaveformControl : Control
{
    private const double HandleGrabPx = 8.0;

    private DragTarget _dragging = DragTarget.None;

    static WaveformControl()
    {
        DefaultStyleKeyProperty.OverrideMetadata(
            typeof(WaveformControl),
            new FrameworkPropertyMetadata(typeof(WaveformControl)));
    }

    public static readonly DependencyProperty WaveformProperty = DependencyProperty.Register(
        nameof(Waveform),
        typeof(Waveform),
        typeof(WaveformControl),
        new FrameworkPropertyMetadata(Waveform.Empty, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty PositionMsProperty = DependencyProperty.Register(
        nameof(PositionMs),
        typeof(double),
        typeof(WaveformControl),
        new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty LoopStartMsProperty = DependencyProperty.Register(
        nameof(LoopStartMs),
        typeof(long),
        typeof(WaveformControl),
        new FrameworkPropertyMetadata(0L, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty LoopEndMsProperty = DependencyProperty.Register(
        nameof(LoopEndMs),
        typeof(long),
        typeof(WaveformControl),
        new FrameworkPropertyMetadata(0L, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty WaveformBrushProperty = DependencyProperty.Register(
        nameof(WaveformBrush),
        typeof(Brush),
        typeof(WaveformControl),
        new FrameworkPropertyMetadata(Brushes.SteelBlue, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty AccentBrushProperty = DependencyProperty.Register(
        nameof(AccentBrush),
        typeof(Brush),
        typeof(WaveformControl),
        new FrameworkPropertyMetadata(Brushes.Goldenrod, FrameworkPropertyMetadataOptions.AffectsRender));

    public Waveform Waveform
    {
        get => (Waveform)GetValue(WaveformProperty);
        set => SetValue(WaveformProperty, value);
    }

    public double PositionMs
    {
        get => (double)GetValue(PositionMsProperty);
        set => SetValue(PositionMsProperty, value);
    }

    public long LoopStartMs
    {
        get => (long)GetValue(LoopStartMsProperty);
        set => SetValue(LoopStartMsProperty, value);
    }

    public long LoopEndMs
    {
        get => (long)GetValue(LoopEndMsProperty);
        set => SetValue(LoopEndMsProperty, value);
    }

    public Brush WaveformBrush
    {
        get => (Brush)GetValue(WaveformBrushProperty);
        set => SetValue(WaveformBrushProperty, value);
    }

    public Brush AccentBrush
    {
        get => (Brush)GetValue(AccentBrushProperty);
        set => SetValue(AccentBrushProperty, value);
    }

    /// <summary>Raised when the user clicks or drags the playhead to a new position.</summary>
    public event EventHandler<double>? SeekRequested;

    /// <summary>Raised while a loop handle is dragged, with the new start and end in milliseconds.</summary>
    public event EventHandler<(long StartMs, long EndMs)>? LoopChanged;

    protected override void OnRender(DrawingContext drawingContext)
    {
        base.OnRender(drawingContext);

        var width = ActualWidth;
        var height = ActualHeight;
        if (width <= 0 || height <= 0)
        {
            return;
        }

        drawingContext.DrawRectangle(Background, null, new Rect(0, 0, width, height));

        var waveform = Waveform;
        var durationMs = waveform.DurationMs;
        if (durationMs <= 0 || waveform.Peaks.Count == 0)
        {
            var noAudio = new FormattedText(
                "No transport stem loaded",
                System.Globalization.CultureInfo.InvariantCulture,
                FlowDirection.LeftToRight,
                new Typeface("Segoe UI"),
                13,
                Foreground,
                VisualTreeHelper.GetDpi(this).PixelsPerDip);
            drawingContext.DrawText(noAudio, new Point(12, height / 2 - noAudio.Height / 2));
            return;
        }

        // Loop region.
        var loopStartX = MsToX(LoopStartMs, durationMs, width);
        var loopEndX = MsToX(LoopEndMs, durationMs, width);
        drawingContext.DrawRectangle(
            new SolidColorBrush(Color.FromArgb(48, 242, 179, 61)),
            null,
            new Rect(loopStartX, 0, Math.Max(1, loopEndX - loopStartX), height));

        // Waveform bars.
        var midY = height / 2.0;
        var barPen = new Pen(WaveformBrush, 1.0);
        barPen.Freeze();
        var binCount = waveform.Peaks.Count;
        var columns = Math.Max(1, (int)width);

        for (var column = 0; column < columns; column++)
        {
            var bin = (int)((long)column * binCount / columns);
            var peak = waveform.Peaks[Math.Min(bin, binCount - 1)];
            var half = peak * (height / 2.0 - 4);
            var x = column + 0.5;
            drawingContext.DrawLine(barPen, new Point(x, midY - half), new Point(x, midY + half));
        }

        // Loop handles.
        var handlePen = new Pen(AccentBrush, 2.0);
        handlePen.Freeze();
        drawingContext.DrawLine(handlePen, new Point(loopStartX, 0), new Point(loopStartX, height));
        drawingContext.DrawLine(handlePen, new Point(loopEndX, 0), new Point(loopEndX, height));
        drawingContext.DrawRectangle(AccentBrush, null, new Rect(loopStartX - 3, 0, 6, 10));
        drawingContext.DrawRectangle(AccentBrush, null, new Rect(loopEndX - 3, height - 10, 6, 10));

        // Playhead.
        var playheadX = MsToX(PositionMs, durationMs, width);
        var playheadPen = new Pen(Brushes.White, 1.5);
        playheadPen.Freeze();
        drawingContext.DrawLine(playheadPen, new Point(playheadX, 0), new Point(playheadX, height));
    }

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonDown(e);

        var durationMs = Waveform.DurationMs;
        if (durationMs <= 0)
        {
            return;
        }

        var x = e.GetPosition(this).X;
        var loopStartX = MsToX(LoopStartMs, durationMs, ActualWidth);
        var loopEndX = MsToX(LoopEndMs, durationMs, ActualWidth);

        _dragging = Math.Abs(x - loopStartX) <= HandleGrabPx
            ? DragTarget.LoopStart
            : Math.Abs(x - loopEndX) <= HandleGrabPx
                ? DragTarget.LoopEnd
                : DragTarget.Playhead;

        CaptureMouse();
        ApplyDrag(x);
        e.Handled = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);

        var durationMs = Waveform.DurationMs;
        if (durationMs <= 0)
        {
            return;
        }

        var x = e.GetPosition(this).X;

        if (_dragging == DragTarget.None)
        {
            var loopStartX = MsToX(LoopStartMs, durationMs, ActualWidth);
            var loopEndX = MsToX(LoopEndMs, durationMs, ActualWidth);
            Cursor = Math.Abs(x - loopStartX) <= HandleGrabPx || Math.Abs(x - loopEndX) <= HandleGrabPx
                ? Cursors.SizeWE
                : Cursors.Arrow;
            return;
        }

        if (e.LeftButton == MouseButtonState.Pressed)
        {
            ApplyDrag(x);
        }
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonUp(e);
        _dragging = DragTarget.None;
        ReleaseMouseCapture();
    }

    private void ApplyDrag(double x)
    {
        var durationMs = Waveform.DurationMs;
        var positionMs = Math.Clamp(x / Math.Max(1.0, ActualWidth) * durationMs, 0, durationMs);

        switch (_dragging)
        {
            case DragTarget.Playhead:
                SeekRequested?.Invoke(this, positionMs);
                break;

            case DragTarget.LoopStart:
            {
                // The handles may not cross; a one-millisecond floor keeps the loop playable.
                var start = (long)Math.Clamp(positionMs, 0, LoopEndMs - 1);
                LoopChanged?.Invoke(this, (start, LoopEndMs));
                break;
            }

            case DragTarget.LoopEnd:
            {
                var end = (long)Math.Clamp(positionMs, LoopStartMs + 1, durationMs);
                LoopChanged?.Invoke(this, (LoopStartMs, end));
                break;
            }
        }
    }

    private static double MsToX(double positionMs, double durationMs, double width) =>
        durationMs <= 0 ? 0 : Math.Clamp(positionMs / durationMs, 0, 1) * width;

    private enum DragTarget
    {
        None,
        Playhead,
        LoopStart,
        LoopEnd
    }
}

using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Win32;

// StayAwake - keeps Windows awake and your messaging apps (Slack/Teams/Discord)
// showing as "Active" by periodically sending a harmless F15 keypress.
// Built for .NET Framework (compiles with csc.exe, no installs required).

namespace StayAwake
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            using (TrayApp app = new TrayApp())
            {
                Application.Run();
            }
        }
    }

    class TrayApp : IDisposable
    {
        // ---- Win32 interop ----
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern uint SetThreadExecutionState(uint esFlags);

        const uint ES_CONTINUOUS = 0x80000000;
        const uint ES_SYSTEM_REQUIRED = 0x00000001;
        const uint ES_DISPLAY_REQUIRED = 0x00000002;

        [DllImport("user32.dll", SetLastError = true)]
        static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [StructLayout(LayoutKind.Sequential)]
        struct INPUT { public uint type; public InputUnion U; }

        [StructLayout(LayoutKind.Explicit)]
        struct InputUnion
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
            [FieldOffset(0)] public HARDWAREINPUT hi;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

        [StructLayout(LayoutKind.Sequential)]
        struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

        [StructLayout(LayoutKind.Sequential)]
        struct HARDWAREINPUT { public uint uMsg; public ushort wParamL; public ushort wParamH; }

        const uint INPUT_KEYBOARD = 1;
        const uint KEYEVENTF_KEYUP = 0x0002;
        const ushort VK_F15 = 0x7E;

        // ---- App state ----
        readonly NotifyIcon tray;
        readonly Timer timer;
        readonly ContextMenuStrip menu;

        readonly ToolStripMenuItem statusItem;
        readonly ToolStripMenuItem enabledItem;
        readonly ToolStripMenuItem displayItem;
        readonly ToolStripMenuItem startupItem;
        readonly ToolStripMenuItem[] intervalItems;

        bool enabled = true;
        bool keepDisplayOn = true;
        int intervalSeconds = 59;

        readonly Icon onIcon;
        readonly Icon offIcon;

        const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string RunValueName = "StayAwake";

        public TrayApp()
        {
            onIcon = BuildIcon(Color.FromArgb(46, 204, 113));   // green = active
            offIcon = BuildIcon(Color.FromArgb(127, 140, 141)); // grey = paused

            menu = new ContextMenuStrip();

            statusItem = new ToolStripMenuItem("Status");
            statusItem.Enabled = false;
            menu.Items.Add(statusItem);
            menu.Items.Add(new ToolStripSeparator());

            enabledItem = new ToolStripMenuItem("Keep me active");
            enabledItem.Checked = true;
            enabledItem.Click += delegate { ToggleEnabled(); };
            menu.Items.Add(enabledItem);

            displayItem = new ToolStripMenuItem("Keep display on");
            displayItem.Checked = true;
            displayItem.Click += delegate { keepDisplayOn = !keepDisplayOn; displayItem.Checked = keepDisplayOn; ApplyState(); };
            menu.Items.Add(displayItem);

            // Interval submenu
            ToolStripMenuItem intervalMenu = new ToolStripMenuItem("Activity interval");
            int[] choices = new int[] { 30, 59, 120, 300 };
            string[] labels = new string[] { "Every 30 seconds", "Every minute", "Every 2 minutes", "Every 5 minutes" };
            intervalItems = new ToolStripMenuItem[choices.Length];
            for (int i = 0; i < choices.Length; i++)
            {
                int secs = choices[i];
                ToolStripMenuItem mi = new ToolStripMenuItem(labels[i]);
                mi.Checked = (secs == intervalSeconds);
                mi.Click += delegate { SetInterval(secs); };
                intervalItems[i] = mi;
                intervalMenu.DropDownItems.Add(mi);
            }
            menu.Items.Add(intervalMenu);

            startupItem = new ToolStripMenuItem("Start with Windows");
            startupItem.Checked = IsStartupEnabled();
            startupItem.Click += delegate { ToggleStartup(); };
            menu.Items.Add(startupItem);

            menu.Items.Add(new ToolStripSeparator());
            ToolStripMenuItem exitItem = new ToolStripMenuItem("Exit");
            exitItem.Click += delegate { ExitApp(); };
            menu.Items.Add(exitItem);

            tray = new NotifyIcon();
            tray.Icon = onIcon;
            tray.Text = "StayAwake";
            tray.ContextMenuStrip = menu;
            tray.Visible = true;
            tray.DoubleClick += delegate { ToggleEnabled(); };

            timer = new Timer();
            timer.Interval = intervalSeconds * 1000;
            timer.Tick += delegate { Pulse(); };

            ApplyState();
            Pulse(); // immediate first pulse
        }

        void Pulse()
        {
            if (!enabled) return;
            SendF15();
            // Refresh execution state each pulse so it never lapses.
            uint flags = ES_CONTINUOUS | ES_SYSTEM_REQUIRED;
            if (keepDisplayOn) flags |= ES_DISPLAY_REQUIRED;
            SetThreadExecutionState(flags);
        }

        void SendF15()
        {
            INPUT[] inputs = new INPUT[2];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki.wVk = VK_F15;
            inputs[1].type = INPUT_KEYBOARD;
            inputs[1].U.ki.wVk = VK_F15;
            inputs[1].U.ki.dwFlags = KEYEVENTF_KEYUP;
            SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT)));
        }

        void ApplyState()
        {
            if (enabled)
            {
                timer.Interval = intervalSeconds * 1000;
                timer.Start();
                uint flags = ES_CONTINUOUS | ES_SYSTEM_REQUIRED;
                if (keepDisplayOn) flags |= ES_DISPLAY_REQUIRED;
                SetThreadExecutionState(flags);
                tray.Icon = onIcon;
            }
            else
            {
                timer.Stop();
                // Release the lock so Windows can sleep normally again.
                SetThreadExecutionState(ES_CONTINUOUS);
                tray.Icon = offIcon;
            }
            UpdateStatusText();
        }

        void UpdateStatusText()
        {
            string state = enabled ? "ACTIVE" : "Paused";
            string detail;
            if (enabled)
            {
                string disp = keepDisplayOn ? ", display on" : "";
                detail = "Pulsing every " + FormatInterval(intervalSeconds) + disp;
            }
            else
            {
                detail = "Sleep allowed";
            }
            statusItem.Text = "StayAwake: " + state;
            // NotifyIcon tooltip is capped at 63 chars.
            string tip = "StayAwake - " + state + " (" + detail + ")";
            if (tip.Length > 63) tip = tip.Substring(0, 63);
            tray.Text = tip;
        }

        string FormatInterval(int secs)
        {
            if (secs % 60 == 0) { int m = secs / 60; return m + (m == 1 ? " min" : " min"); }
            return secs + "s";
        }

        void ToggleEnabled()
        {
            enabled = !enabled;
            enabledItem.Checked = enabled;
            ApplyState();
        }

        void SetInterval(int secs)
        {
            intervalSeconds = secs;
            for (int i = 0; i < intervalItems.Length; i++)
            {
                intervalItems[i].Checked = (intervalItems[i].Text != null) && false;
            }
            // Re-check the matching item by value.
            string[] labels = new string[] { "Every 30 seconds", "Every minute", "Every 2 minutes", "Every 5 minutes" };
            int[] choices = new int[] { 30, 59, 120, 300 };
            for (int i = 0; i < intervalItems.Length; i++)
                intervalItems[i].Checked = (choices[i] == secs);
            ApplyState();
        }

        // ---- Startup (registry Run key) ----
        bool IsStartupEnabled()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKeyPath, false))
                {
                    if (key == null) return false;
                    object val = key.GetValue(RunValueName);
                    return val != null;
                }
            }
            catch { return false; }
        }

        void ToggleStartup()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKeyPath, true))
                {
                    if (key == null) return;
                    if (IsStartupEnabled())
                    {
                        key.DeleteValue(RunValueName, false);
                        startupItem.Checked = false;
                    }
                    else
                    {
                        string exe = Application.ExecutablePath;
                        key.SetValue(RunValueName, "\"" + exe + "\"");
                        startupItem.Checked = true;
                    }
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show("Could not change startup setting: " + ex.Message,
                    "StayAwake", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        // ---- Tray icon drawing ----
        Icon BuildIcon(Color color)
        {
            using (Bitmap bmp = new Bitmap(32, 32))
            {
                using (Graphics g = Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                    g.Clear(Color.Transparent);
                    using (SolidBrush b = new SolidBrush(color))
                        g.FillEllipse(b, 2, 2, 28, 28);
                    using (Pen p = new Pen(Color.White, 3))
                    {
                        // simple "eye open" / dot to suggest awake
                        g.DrawEllipse(p, 10, 10, 12, 12);
                        using (SolidBrush wb = new SolidBrush(Color.White))
                            g.FillEllipse(wb, 13, 13, 6, 6);
                    }
                }
                IntPtr hicon = bmp.GetHicon();
                return (Icon)Icon.FromHandle(hicon).Clone();
            }
        }

        void ExitApp()
        {
            SetThreadExecutionState(ES_CONTINUOUS); // release lock
            tray.Visible = false;
            Application.ExitThread();
        }

        public void Dispose()
        {
            if (timer != null) timer.Dispose();
            if (tray != null) { tray.Visible = false; tray.Dispose(); }
            if (menu != null) menu.Dispose();
            if (onIcon != null) onIcon.Dispose();
            if (offIcon != null) offIcon.Dispose();
            SetThreadExecutionState(ES_CONTINUOUS);
        }
    }
}

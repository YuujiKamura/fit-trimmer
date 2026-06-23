# -*- coding: utf-8 -*-
import tkinter as tk
from tkinter import ttk
import glob, json, os

class HudMonitor:
    def __init__(self, root):
        self.root = root
        self.root.title("FitTrimmer - Job Monitor")
        self.root.geometry("400x300")
        self.root.attributes("-topmost", True) # 常に最前面
        self.root.configure(bg='#1e1e1e')

        self.label = tk.Label(root, text="🚴 Active Encoding Jobs", fg='white', bg='#1e1e1e', font=('Segoe UI', 12, 'bold'))
        self.label.pack(pady=10)

        self.jobs_frame = tk.Frame(root, bg='#1e1e1e')
        self.jobs_frame.pack(fill='both', expand=True, padx=20)

        self.progress_bars = {}
        self.update_jobs()

    def update_jobs(self):
        # フォルダ内の status.json を探す
        status_files = glob.glob("H:/マイドライブ/20260621/*.status.json")

        # 既存のウィジェットをクリア（簡易実装）
        for widget in self.jobs_frame.winfo_children():
            widget.destroy()

        if not status_files:
            tk.Label(self.jobs_frame, text="No active jobs.", fg='#888', bg='#1e1e1e').pack()
        else:
            for sf in status_files:
                try:
                    with open(sf, 'r') as f:
                        data = json.load(f)

                    name = os.path.basename(sf).replace(".status.json", "")
                    prog = data['progress']

                    lbl = tk.Label(self.jobs_frame, text=f"🎬 {name[:25]}...", fg='white', bg='#1e1e1e', anchor='w')
                    lbl.pack(fill='x', pady=(5, 0))

                    pb = ttk.Progressbar(self.jobs_frame, length=100, mode='determinate', value=prog)
                    pb.pack(fill='x', pady=(0, 5))

                    perc = tk.Label(self.jobs_frame, text=f"{prog}% ({data['sec']}/{data['total']}s)", fg='#3b82f6', bg='#1e1e1e', font=('Consolas', 9))
                    perc.pack(fill='x')
                except:
                    pass

        self.root.after(2000, self.update_jobs) # 2秒ごとに更新

if __name__ == "__main__":
    root = tk.Tk()
    app = HudMonitor(root)
    root.mainloop()

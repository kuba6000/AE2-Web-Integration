"""Keep owned Windows process trees tied to their launching process lifetime."""
import ctypes
from ctypes import wintypes
import os
import subprocess


class ProcessGroup:
    """Windows job closes the entire tree on normal exit or abrupt parent death.

    On POSIX, callers retain ownership by passing their flock lease descriptor to
    the child. The child then prevents resetting the slot if its parent is killed.
    """
    def __init__(self):
        self.job = None
        if os.name != 'nt':
            return
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.kernel = kernel
        kernel.CreateJobObjectW.restype = wintypes.HANDLE
        kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE

        class BasicLimits(ctypes.Structure):
            _fields_ = [('process_time', ctypes.c_int64), ('job_time', ctypes.c_int64),
                        ('flags', wintypes.DWORD), ('min_working_set', ctypes.c_size_t),
                        ('max_working_set', ctypes.c_size_t), ('process_limit', wintypes.DWORD),
                        ('affinity', ctypes.c_size_t), ('priority', wintypes.DWORD),
                        ('scheduling', wintypes.DWORD)]

        class ExtendedLimits(ctypes.Structure):
            _fields_ = [('basic', BasicLimits), ('io_counters', ctypes.c_uint64 * 6),
                        ('process_memory', ctypes.c_size_t), ('job_memory', ctypes.c_size_t),
                        ('peak_process_memory', ctypes.c_size_t), ('peak_job_memory', ctypes.c_size_t)]

        self.job = kernel.CreateJobObjectW(None, None)
        limits = ExtendedLimits()
        limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.job or not kernel.SetInformationJobObject(self.job, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.close()
            raise ctypes.WinError(ctypes.get_last_error())

    def start(self, command, **kwargs):
        if os.name == 'nt':
            kwargs['creationflags'] = kwargs.get('creationflags', 0) | 0x4  # CREATE_SUSPENDED
        process = subprocess.Popen(command, **kwargs)
        if os.name != 'nt':
            return process
        handle = self.kernel.OpenProcess(0x0101, False, process.pid)  # SET_QUOTA | TERMINATE
        try:
            if not handle or not self.kernel.AssignProcessToJobObject(self.job, handle):
                raise ctypes.WinError(ctypes.get_last_error())
            self.resume(process.pid)
        except BaseException:
            process.kill()
            process.wait()
            raise
        finally:
            if handle:
                self.kernel.CloseHandle(handle)
        return process

    def resume(self, pid):
        kernel = self.kernel

        class ThreadEntry(ctypes.Structure):
            _fields_ = [('size', wintypes.DWORD), ('usage', wintypes.DWORD), ('id', wintypes.DWORD),
                        ('owner', wintypes.DWORD), ('priority', wintypes.LONG),
                        ('delta', wintypes.LONG), ('flags', wintypes.DWORD)]

        kernel.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
        kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
        kernel.Thread32First.argtypes = [wintypes.HANDLE, ctypes.POINTER(ThreadEntry)]
        kernel.Thread32Next.argtypes = [wintypes.HANDLE, ctypes.POINTER(ThreadEntry)]
        kernel.OpenThread.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenThread.restype = wintypes.HANDLE
        kernel.ResumeThread.argtypes = [wintypes.HANDLE]
        kernel.ResumeThread.restype = wintypes.DWORD
        snapshot = kernel.CreateToolhelp32Snapshot(4, 0)
        if snapshot == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            entry = ThreadEntry()
            entry.size = ctypes.sizeof(entry)
            found = kernel.Thread32First(snapshot, ctypes.byref(entry))
            while found:
                if entry.owner == pid:
                    thread = kernel.OpenThread(2, False, entry.id)
                    try:
                        if not thread or kernel.ResumeThread(thread) == 0xFFFFFFFF:
                            raise ctypes.WinError(ctypes.get_last_error())
                        return
                    finally:
                        if thread:
                            kernel.CloseHandle(thread)
                found = kernel.Thread32Next(snapshot, ctypes.byref(entry))
            raise OSError('Could not locate the suspended child thread')
        finally:
            kernel.CloseHandle(snapshot)

    def close(self):
        if self.job:
            self.kernel.CloseHandle(self.job)
            self.job = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

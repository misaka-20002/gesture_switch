"use strict";

const script = "sh /data/adb/modules/gesture_switch/settings.sh";
const labels = new Map([
    ["gestural", "手势导航"],
    ["threebutton", "三键导航"],
    ["twobutton", "两键导航"],
]);
const form = document.querySelector("#settings");
const fields = document.querySelector("#fields");
const folded = document.querySelector("#folded");
const opened = document.querySelector("#opened");
const debounce = document.querySelector("#debounce");
const save = document.querySelector("#save");
const status = document.querySelector("#status");
let available = new Set();
let savedModes = [];
let savedDebounce;
let requestId = 0;

function run(command) {
    return new Promise((resolve, reject) => {
        if (typeof globalThis.ksu?.exec !== "function") {
            reject(new Error("请从 KernelSU 模块卡片打开设置。"));
            return;
        }
        // The manager invokes the named global with the command's exit status and output.
        const callback = `gestureSwitchSettingsResult${++requestId}`;
        const timer = setTimeout(() => {
            delete globalThis[callback];
            reject(new Error("读取或保存超时，请重新打开设置确认当前值。"));
        }, 10000);
        globalThis[callback] = (code, stdout, stderr) => {
            clearTimeout(timer);
            delete globalThis[callback];
            if (code === 0) resolve(stdout);
            else reject(new Error(stderr.trim() || "设置命令执行失败。"));
        };
        try {
            globalThis.ksu.exec(command, "{}", callback);
        } catch (error) {
            clearTimeout(timer);
            delete globalThis[callback];
            reject(error);
        }
    });
}

function populate(select, selected) {
    select.replaceChildren();
    for (const [mode, label] of labels) {
        if (!available.has(mode) && mode !== selected) continue;
        const option = new Option(available.has(mode) ? label : `${label}（不可用）`, mode);
        option.disabled = !available.has(mode);
        select.add(option);
    }
    select.value = selected;
}

function validDebounce(value) {
    return /^[0-9]{1,4}$/.test(value) && Number(value) <= 5000;
}

function canSave() {
    return available.has(folded.value) && available.has(opened.value)
        && validDebounce(debounce.value)
        && (folded.value !== savedModes[0] || opened.value !== savedModes[1]
            || Number(debounce.value) !== savedDebounce);
}

async function load() {
    try {
        const [foldedMode, openMode, delay, installed = ""] =
            (await run(`${script} status`)).split("\n");
        if (!labels.has(foldedMode) || !labels.has(openMode) || !validDebounce(delay)) {
            throw new Error("无法读取已保存的设置。");
        }
        available = new Set(installed.split(/\s+/).filter(mode => labels.has(mode)));
        savedModes = [foldedMode, openMode];
        savedDebounce = Number(delay);
        debounce.value = String(savedDebounce);
        populate(folded, foldedMode);
        populate(opened, openMode);
        fields.disabled = available.size === 0;
        status.textContent = available.size
            ? "防抖可设为 0–5000 毫秒。保存后实时更新。"
            : "当前用户没有可切换的导航模式。";
    } catch (error) {
        status.textContent = error.message;
    }
}

form.addEventListener("input", () => { save.disabled = !canSave(); });
form.addEventListener("submit", async event => {
    event.preventDefault();
    if (fields.disabled || !canSave()) return;

    const selection = [folded.value, opened.value];
    const delay = Number(debounce.value);
    fields.disabled = true;
    status.textContent = "正在保存…";
    try {
        // canSave validates both mode tokens and the numeric argument before shell execution.
        const result = await run(`${script} save ${selection[0]} ${selection[1]} ${delay}`);
        savedModes = selection;
        savedDebounce = delay;
        debounce.value = String(delay);
        populate(folded, savedModes[0]);
        populate(opened, savedModes[1]);
        status.textContent = result.trim() === "applied"
            ? "已保存并应用，无需重启。"
            : "已保存，运行中的模块未确认更新。模块下次启动时会读取这些设置。";
    } catch (error) {
        status.textContent = error.message;
    } finally {
        fields.disabled = false;
        save.disabled = !canSave();
    }
});

load();

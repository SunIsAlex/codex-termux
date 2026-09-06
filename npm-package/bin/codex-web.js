#!/data/data/com.termux/files/usr/bin/node
import { start } from '../web-ui/server.mjs';
start(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });

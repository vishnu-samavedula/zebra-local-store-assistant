#!/usr/bin/env ruby

require "json"
require "net/http"

$stdout.sync = true

root = File.expand_path("..", __dir__)
system_prompt =
  [File.read(File.join(root, "contracts/warehouse_tool_agent_native_v1.md")).strip, ENV["SYSTEM_SUFFIX"]]
    .compact
    .reject(&:empty?)
    .join("\n")
tools = JSON.parse(File.read(File.join(root, "contracts/warehouse_tools_v1.json")))
base = URI(ARGV.fetch(0, "http://127.0.0.1:18080"))

default_prompts = [
  "Check how many Blue TrailBlaze GTX size 10.5 are available.",
  "What's in D3-01?",
  "What's the status of task T-122?",
  "Generate a damage report for exactly 12 units of SKU-1809-BLK-090 at A3-05.",
  "Add exactly 8 units of SKU-4103-BLK-100 to C1-02.",
  "Give me the location of Harbor Classic T-shirts, Metro Zip Wallets, and Metro Run Sneaker.",
].freeze
prompts = ENV["PROMPT"]&.then { |prompt| [prompt] } || default_prompts

def post_json(base, path, payload)
  endpoint = base.dup
  endpoint.path = path
  request = Net::HTTP::Post.new(endpoint, "Content-Type" => "application/json")
  request.body = JSON.generate(payload)
  started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  response = Net::HTTP.start(endpoint.host, endpoint.port, read_timeout: 180) { |http| http.request(request) }
  elapsed_ms = ((Process.clock_gettime(Process::CLOCK_MONOTONIC) - started) * 1_000).round
  raise "HTTP #{response.code}: #{response.body[0, 500]}" unless response.is_a?(Net::HTTPSuccess)
  [JSON.parse(response.body), elapsed_ms]
end

prompts.first(ENV.fetch("LIMIT", prompts.length).to_i).each_with_index do |prompt, index|
  rendered, = post_json(
    base,
    "/apply-template",
    messages: [{ role: "system", content: system_prompt }, { role: "user", content: prompt }],
    tools: tools,
    add_generation_prompt: true,
  )
  completion, wall_ms = post_json(
    base,
    "/completion",
    prompt: rendered.fetch("prompt"),
    n_predict: 256,
    temperature: 0,
    repeat_penalty: 1.1,
    repeat_last_n: 128,
    stop: ["<|tool_call_end|>", "<|im_end|>"],
    cache_prompt: true,
  )
  timings = completion.fetch("timings", {})
  puts JSON.generate(
    index: index,
    prompt: prompt,
    wall_ms: wall_ms,
    prompt_n: timings["prompt_n"],
    prompt_ms: timings["prompt_ms"],
    prompt_tps: timings["prompt_per_second"],
    predicted_n: timings["predicted_n"],
    predicted_ms: timings["predicted_ms"],
    predicted_tps: timings["predicted_per_second"],
    cached_n: completion["tokens_cached"],
    stop_type: completion["stop_type"],
    content: completion.fetch("content", "")[0, 1_000],
  )
end

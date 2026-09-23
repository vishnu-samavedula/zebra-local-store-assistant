#!/usr/bin/env ruby

require "json"
require "net/http"

endpoint = URI(ARGV.fetch(0, "http://127.0.0.1:18080/v1/chat/completions"))
system_prompt = <<~PROMPT.strip
  You are an offline warehouse tool agent on a Zebra handheld.
  Use only the supplied tools. Emit at most three independent reads in one turn.
  Dependent calls must wait for tool results. A write must be the only call in its
  turn and is only a proposal that the app will confirm. Never emit SQL or hidden
  reasoning. Ask a short clarification when required information is missing.
  After read results, answer in at most two short sentences using only returned facts.
PROMPT

string = { type: "string" }
integer = { type: "integer", minimum: 1 }
definitions = {
  "inventory_search" => ["Search local inventory by product description, SKU, attributes, location, or required quantity.", { semantic_query: string, sku: string, color: string, size: string, location: string, minimum_quantity: integer }, []],
  "location_contents" => ["List or filter inventory stored at a warehouse bin, aisle, dock, staging area, or zone.", { location: string, semantic_query: string }, %w[location]],
  "get_task_status" => ["Retrieve one warehouse task or filter the current worker's assigned tasks.", { task_id: string, task_type: { type: "string", enum: %w[pick putaway receive replenish] }, status: { type: "string", enum: %w[assigned in_progress blocked complete] } }, []],
  "report_issue" => ["Propose a warehouse issue report. The app previews and confirms every write.", { description: string, category: { type: "string", enum: %w[damage discrepancy blocked_location general] }, semantic_query: string, sku: string, quantity: integer, location: string, task_id: string }, %w[description category]],
  "request_replenishment" => ["Propose replenishment to a destination. The app previews and confirms every write.", { semantic_query: string, sku: string, quantity: integer, quantity_mode: { type: "string", enum: %w[add target_level] }, destination_location: string, reason: string, task_id: string }, %w[quantity quantity_mode destination_location]],
}
tools = definitions.map do |name, (description, properties, required)|
  parameters = { type: "object", properties: properties, additionalProperties: false }
  parameters[:required] = required unless required.empty?
  { type: "function", function: { name: name, description: description, parameters: parameters } }
end

cases = {
  "inventory_search" => "Check how many Blue TrailBlaze GTX size 10.5 are available.",
  "location_contents" => "What's in D3-01?",
  "get_task_status" => "What's the status of task T-122?",
  "report_issue" => "Generate a damage report for exactly 12 units of SKU-1809-BLK-090 at A3-05.",
  "request_replenishment" => "Can you add exactly 8 units of SKU-4103-BLK-100 to C1-02?",
}
cases = { "custom" => ENV.fetch("PROMPT") } if ENV["PROMPT"]

if ENV["APPLY_TEMPLATE"] == "1"
  template_endpoint = endpoint.dup
  template_endpoint.path = "/apply-template"
  request = Net::HTTP::Post.new(template_endpoint, "Content-Type" => "application/json")
  request.body = JSON.generate(
    messages: [{ role: "system", content: system_prompt }, { role: "user", content: cases.values.first }],
    tools: tools,
    add_generation_prompt: true,
  )
  response = Net::HTTP.start(template_endpoint.host, template_endpoint.port) { |http| http.request(request) }
  puts response.body
  exit(response.is_a?(Net::HTTPSuccess) ? 0 : 1)
end

cases.each do |expected, prompt|
  if ENV["RAW_COMPLETION"] == "1"
    template_endpoint = endpoint.dup
    template_endpoint.path = "/apply-template"
    template_request = Net::HTTP::Post.new(template_endpoint, "Content-Type" => "application/json")
    template_request.body = JSON.generate(
      messages: [{ role: "system", content: system_prompt }, { role: "user", content: prompt }],
      tools: tools,
      add_generation_prompt: true,
    )
    template_response = Net::HTTP.start(template_endpoint.host, template_endpoint.port) { |http| http.request(template_request) }
    rendered = JSON.parse(template_response.body).fetch("prompt")
    completion_endpoint = endpoint.dup
    completion_endpoint.path = "/completion"
    request = Net::HTTP::Post.new(completion_endpoint, "Content-Type" => "application/json")
    request.body = JSON.generate(
      prompt: rendered,
      n_predict: 128,
      temperature: 0,
      repeat_penalty: 1.1,
      repeat_last_n: 128,
      stop: ["<|im_end|>"],
      cache_prompt: true,
    )
    response = Net::HTTP.start(completion_endpoint.host, completion_endpoint.port, read_timeout: 180) { |http| http.request(request) }
    parsed = JSON.parse(response.body)
    puts JSON.generate(expected: expected, stop: parsed["stop"], content: parsed["content"])
    next
  end
  request = Net::HTTP::Post.new(endpoint, "Content-Type" => "application/json")
  request.body = JSON.generate(
    messages: [{ role: "system", content: system_prompt }, { role: "user", content: prompt }],
    tools: tools,
    tool_choice: "auto",
    temperature: 0,
    repeat_penalty: 1.1,
    repeat_last_n: 128,
    max_tokens: 128,
    stream: false,
    cache_prompt: true,
  )
  response = Net::HTTP.start(endpoint.host, endpoint.port, read_timeout: 180) { |http| http.request(request) }
  parsed = JSON.parse(response.body)
  choice = parsed.fetch("choices").first
  calls = choice.dig("message", "tool_calls") || []
  actual = calls.first&.dig("function", "name")
  arguments = calls.first&.dig("function", "arguments")
  puts JSON.generate(expected: expected, actual: actual, finish_reason: choice["finish_reason"], arguments: arguments)
end

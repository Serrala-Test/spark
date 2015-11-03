#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

require 'liquid'
require 'pygments'

module Jekyll
  class IncludeExampleTag < Liquid::Tag

    FileOnly = /^(\S+\.\S+)$/
    FileTag = /^(\S+\.\S+)\s+(\S+)$/
    
    def initialize(tag_name, markup, tokens)
      super
      clean_markup = markup.strip
      if clean_markup =~ FileOnly
        @markup = $1
        @label = ''
      elsif clean_markup =~ FileTag
        @markup = $1
        @label = " #{$2}"
      else
        raise "Invalid syntax. Use {% include_example path/to/file [label] %}."
      end
    end
 
    def render(context)
      site = context.registers[:site]
      config_dir = (site.config['code_dir'] || '../examples/src/main').sub(/^\//,'')
      @code_dir = File.join(site.source, config_dir)

      @file = File.join(@code_dir, @markup)
      @lang = @markup.split('.').last

      code = File.open(@file).read.encode("UTF-8")
      code = select_lines(code)
 
      Pygments.highlight(code, :lexer => @lang)
    end
 
    # Trim the code block so as to have the same indention, regardless of their positions in the
    # code file.
    def trim_codeblock(lines)
      # Select the minimum indention of the current code block.
      min_start_spaces = lines
        .select { |l| l.strip.size !=0 }
        .map { |l| l[/\A */].size }
        .min

      lines.map { |l| l[min_start_spaces .. -1] }
    end

    # Select lines according to labels in code. Currently we use "$example on$" and "$example off$"
    # as labels. Note that code blocks identified by the labels should not overlap.
    def select_lines(code)
      lines = code.each_line.to_a

      # Select the array of start labels from code.
      startIndices = lines
        .each_with_index
        .select { |l, i| l.include? "$example on#{@label}$" }
        .map { |l, i| i }

      # Select the array of end labels from code.
      endIndices = lines
        .each_with_index
        .select { |l, i| l.include? "$example off#{@label}$" }
        .map { |l, i| i }

      raise "Start indices amount is not equal to end indices amount, please check the code." \
        unless startIndices.size == endIndices.size

      raise "No code is selected by include_example, please check the code." \
        if startIndices.size == 0

      # Select and join code blocks together, with a space line between each of two continuous
      # blocks.
      lastIndex = -1
      result = ""
      startIndices.zip(endIndices).each do |start, endline|
        raise "Overlapping between two example code blocks are not allowed." if start <= lastIndex
        raise "$example on$ should not be in the same line with $example off$." if start == endline
        lastIndex = endline
        range = Range.new(start + 1, endline - 1)
        result += trim_codeblock(lines[range]).join
        result += "\n"
      end
      result
    end
  end
end

Liquid::Template.register_tag('include_example', Jekyll::IncludeExampleTag)

package hullarb.examlpes.ffmpeg;


import org.bytedeco.javacpp.*;

import java.io.IOException;

import static org.bytedeco.javacpp.avcodec.*;
import static org.bytedeco.javacpp.avfilter.*;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avutil.*;
import static org.bytedeco.javacpp.presets.avutil.AVERROR_EAGAIN;

/**
 * java javacpp-presets/ffmpeg translation of the transcoding ffmpeg official example
 * https://github.com/FFmpeg/FFmpeg/blob/n3.4.1/doc/examples/transcoding.c
 * @author Bela Hullar
 */
public class Transcoding {


    static class FilteringContext {
        AVFilterContext bufferSinkContext;
        AVFilterContext bufferSourceContext;
        AVFilterGraph filterGraph;
    }

    static FilteringContext[] filteringContexts;

    static class StreamContext {
        AVCodecContext decoderContext;
        AVCodecContext encoderContext;
    }

    static StreamContext[] streamContexts;

    static AVFormatContext inputFormatContext;
    static AVFormatContext outputFormatContext;

    static void check(int err) {
        if (err < 0) {
            BytePointer e = new BytePointer(512);
            av_strerror(err, e, 512);
            throw new RuntimeException(e.getString().substring(0, (int) BytePointer.strlen(e)) + ":" + err);
        }
    }

    static void openInput(String fileName) {
        inputFormatContext = new AVFormatContext(null);
        check(avformat_open_input(inputFormatContext, fileName, null, null));
        check(avformat_find_stream_info(inputFormatContext, (PointerPointer) null));
        streamContexts = new StreamContext[inputFormatContext.nb_streams()];
        for (int i = 0; i < inputFormatContext.nb_streams(); i++) {
            streamContexts[i] = new StreamContext();
            AVStream stream = inputFormatContext.streams(i);
            AVCodec decoder = avcodec_find_decoder(stream.codecpar().codec_id());
            if (decoder == null) new RuntimeException("Unexpected decore: " + stream.codecpar().codec_id());
            AVCodecContext codecContext = avcodec_alloc_context3(decoder);
            check(avcodec_parameters_to_context(codecContext, stream.codecpar()));
            if (codecContext.codec_type() == AVMEDIA_TYPE_VIDEO || codecContext.codec_type() == AVMEDIA_TYPE_AUDIO) {
                if (codecContext.codec_type() == AVMEDIA_TYPE_VIDEO) {
                    codecContext.framerate(av_guess_frame_rate(inputFormatContext, stream, null));
                }
                check(avcodec_open2(codecContext, decoder, (AVDictionary) null));
            }
            streamContexts[i].decoderContext = codecContext;
        }
        av_dump_format(inputFormatContext, 0, fileName, 0);
    }

    static AVFormatContext openOutput(String fileName) {
        outputFormatContext = new AVFormatContext(null);
        check(avformat_alloc_output_context2(outputFormatContext, null, null, fileName));
        for (int i = 0; i < inputFormatContext.nb_streams(); i++) {
            AVCodec c = new AVCodec(null);
            AVStream outStream = avformat_new_stream(outputFormatContext, c);
            AVStream inStream = inputFormatContext.streams(i);
            AVCodecContext decoderContext = streamContexts[i].decoderContext;
            if (decoderContext.codec_type() == AVMEDIA_TYPE_VIDEO ||
                    decoderContext.codec_type() == AVMEDIA_TYPE_AUDIO) {
                AVCodec encoder = avcodec_find_encoder(decoderContext.codec_id());
                AVCodecContext encoderContext = avcodec_alloc_context3(encoder);
                if (decoderContext.codec_type() == AVMEDIA_TYPE_VIDEO) {
                    encoderContext.height(decoderContext.height());
                    encoderContext.width(decoderContext.width());
                    encoderContext.sample_aspect_ratio(decoderContext.sample_aspect_ratio());
                    if (encoder.pix_fmts() != null && encoder.pix_fmts().asBuffer() != null) {
                        encoderContext.pix_fmt(encoder.pix_fmts().get(0));
                    } else {
                        encoderContext.pix_fmt(decoderContext.pix_fmt());
                    }
                    encoderContext.time_base(av_inv_q(decoderContext.framerate()));
                } else {
                    encoderContext.sample_rate(decoderContext.sample_rate());
                    encoderContext.channel_layout(decoderContext.channel_layout());
                    encoderContext.channels(av_get_channel_layout_nb_channels(encoderContext.channel_layout()));
                    encoderContext.sample_fmt(encoder.sample_fmts().get(0));
                    encoderContext.time_base(av_make_q(1, encoderContext.sample_rate()));
                }

                check(avcodec_open2(encoderContext, encoder, (AVDictionary) null));
                check(avcodec_parameters_from_context(outStream.codecpar(), encoderContext));
                if ((outputFormatContext.oformat().flags() & AVFMT_GLOBALHEADER) == AVFMT_GLOBALHEADER) {
                    encoderContext.flags(encoderContext.flags() | CODEC_FLAG_GLOBAL_HEADER);
                }
                outStream.time_base(encoderContext.time_base());
                streamContexts[i].encoderContext = encoderContext;
            } else {
                if (decoderContext.codec_type() == AVMEDIA_TYPE_UNKNOWN) {
                    throw new RuntimeException();
                } else {
                    check(avcodec_parameters_copy(outStream.codecpar(), inStream.codecpar()));
                    outStream.time_base(inStream.time_base());
                }
            }
        }
        av_dump_format(outputFormatContext, 0, fileName, 1);

        if ((outputFormatContext.flags() & AVFMT_NOFILE) != AVFMT_NOFILE) {
            AVIOContext c = new AVIOContext();
            check(avio_open(c, fileName, AVIO_FLAG_WRITE));
            outputFormatContext.pb(c);
        }

        check(avformat_write_header(outputFormatContext, (AVDictionary) null));
        return outputFormatContext;
    }

    static void initFilter(FilteringContext filteringContext, AVCodecContext decoderContext,
                           AVCodecContext encoderContext, String filterSpec) {
        AVFilterInOut outputs = avfilter_inout_alloc();
        AVFilterInOut inputs = avfilter_inout_alloc();
        AVFilterGraph filterGraph = avfilter_graph_alloc();
        AVFilter buffersink = null;
        AVFilter buffersrc = null;
        AVFilterContext buffersrcContext = new AVFilterContext();
        AVFilterContext buffersinkContext = new AVFilterContext();
        try {

            if (decoderContext.codec_type() == AVMEDIA_TYPE_VIDEO) {
                buffersrc = avfilter_get_by_name("buffer");
                buffersink = avfilter_get_by_name("buffersink");
                String args = String.format("video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:pixel_aspect=%d/%d",
                        decoderContext.width(), decoderContext.height(), decoderContext.pix_fmt(),
                        decoderContext.time_base().num(), decoderContext.time_base().den(),
                        decoderContext.sample_aspect_ratio().num(), decoderContext.sample_aspect_ratio().den());
                check(avfilter_graph_create_filter(buffersrcContext, buffersrc, "in", args, null, filterGraph));
                check(avfilter_graph_create_filter(buffersinkContext, buffersink, "out", null, null, filterGraph));
                BytePointer pixFmt = new BytePointer(4).putInt(encoderContext.pix_fmt());
                check(av_opt_set_bin(buffersinkContext, "pix_fmts", pixFmt, 4, AV_OPT_SEARCH_CHILDREN));
            } else {
                if (decoderContext.codec_type() == AVMEDIA_TYPE_AUDIO) {
                    buffersrc = avfilter_get_by_name("abuffer");
                    buffersink = avfilter_get_by_name("abuffersink");
                    if (decoderContext.channel_layout() == 0) {
                        decoderContext.channel_layout(av_get_default_channel_layout(decoderContext.channels()));
                    }
                    BytePointer name = new BytePointer(100);
                    av_get_channel_layout_string(name, 100, decoderContext.channels(), decoderContext.channel_layout());
                    String chLayout = name.getString().substring(0, (int) BytePointer.strlen(name));
                    String args = String.format("time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
                            decoderContext.time_base().num(), decoderContext.time_base().den(), decoderContext.sample_rate(),
                            av_get_sample_fmt_name(decoderContext.sample_fmt()).getString(), chLayout);
                    check(avfilter_graph_create_filter(buffersrcContext, buffersrc, "in", args, null, filterGraph));
                    check(avfilter_graph_create_filter(buffersinkContext, buffersink, "out", null, null, filterGraph));
                    BytePointer smplFmt = new BytePointer(4).putInt(encoderContext.sample_fmt());
                    check(av_opt_set_bin(buffersinkContext, "sample_fmts", smplFmt, 4,
                            AV_OPT_SEARCH_CHILDREN));
                    BytePointer chL = new BytePointer(8).putLong(encoderContext.channel_layout());
                    check(av_opt_set_bin(buffersinkContext, "channel_layouts", chL, 8,
                            AV_OPT_SEARCH_CHILDREN));
                    BytePointer sr = new BytePointer(8).putLong(encoderContext.sample_rate());
                    check(av_opt_set_bin(buffersinkContext, "sample_rates", sr, 8,
                            AV_OPT_SEARCH_CHILDREN));
                } else {
                    throw new RuntimeException();
                }
            }
            outputs.name(new BytePointer(av_strdup("in")));
            outputs.filter_ctx(buffersrcContext);
            outputs.pad_idx(0);
            outputs.next(null);

            inputs.name(new BytePointer(av_strdup("out")));
            inputs.filter_ctx(buffersinkContext);
            inputs.pad_idx(0);
            inputs.next(null);

            check(avfilter_graph_parse_ptr(filterGraph, filterSpec, inputs, outputs, null));
            check(avfilter_graph_config(filterGraph, null));

            filteringContext.bufferSourceContext = buffersrcContext;
            filteringContext.bufferSinkContext = buffersinkContext;
            filteringContext.filterGraph = filterGraph;
        } finally {
            avfilter_inout_free(inputs);
            avfilter_inout_free(outputs);
        }
    }

    static void initFilters() {
        filteringContexts = new FilteringContext[inputFormatContext.nb_streams()];
        for (int i = 0; i < inputFormatContext.nb_streams(); i++) {
            String filterSpec = null;
            if (!(inputFormatContext.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO
                    || inputFormatContext.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO)) {
                continue;
            }
            filteringContexts[i] = new FilteringContext();
            if (inputFormatContext.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                filterSpec = "null";
            } else {
                filterSpec = "anull";
            }
            initFilter(filteringContexts[i], streamContexts[i].decoderContext, streamContexts[i].encoderContext, filterSpec);
        }
    }


    static boolean encodeWriteFrame(AVFrame filterFrame, int streamIndex) {
        AVPacket encodedPacket = new AVPacket();
        encodedPacket.data(null);
        encodedPacket.size(0);
        av_init_packet(encodedPacket);
        int[] gotFrameLocal = new int[1];
        if (inputFormatContext.streams(streamIndex).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
            check(avcodec_encode_video2(streamContexts[streamIndex].encoderContext, encodedPacket, filterFrame, gotFrameLocal));
        } else {
            check(avcodec_encode_audio2(streamContexts[streamIndex].encoderContext, encodedPacket, filterFrame, gotFrameLocal));
        }
        av_frame_free(filterFrame);
        if (gotFrameLocal[0] == 0) {
            return false;
        }
        encodedPacket.stream_index(streamIndex);
        av_packet_rescale_ts(encodedPacket, streamContexts[streamIndex].encoderContext.time_base(),
                outputFormatContext.streams(streamIndex).time_base());
        check(av_interleaved_write_frame(outputFormatContext, encodedPacket));
        return true;
    }

    static void filterEncodeWriteFrame(AVFrame frame, int streamIndex) {
        check(av_buffersrc_add_frame_flags(filteringContexts[streamIndex].bufferSourceContext,
                frame, 0));
        while (true) {
            AVFrame filterFrame = av_frame_alloc();
            int ret = av_buffersink_get_frame(filteringContexts[streamIndex].bufferSinkContext, filterFrame);
            if (ret == AVERROR_EOF() || ret == AVERROR_EAGAIN()) {
                av_frame_free(filterFrame);
                return;
            }
            check(ret);
            filterFrame.pict_type(AV_PICTURE_TYPE_NONE);
            encodeWriteFrame(filterFrame, streamIndex);
        }
    }

    static void flushEncoder(int streamIndex) {
        if ((streamContexts[streamIndex].encoderContext.codec().capabilities() & AV_CODEC_CAP_DELAY)
                != AV_CODEC_CAP_DELAY) {
            return;
        }
        while (encodeWriteFrame(null, streamIndex)) ;
    }

    public static void main(String[] args) throws IOException {

        if (args.length < 2) {
            System.out.println("Usage:Transcoding <input> <output>");
            System.exit(-1);
        }
        // Register all formats and codecs
        av_register_all();
        avfilter_register_all();

        openInput(args[0]);
        openOutput(args[1]);
        initFilters();
        try {
            int[] gotFrame = new int[1];
            AVPacket packet = new AVPacket();
            while (av_read_frame(inputFormatContext, packet) >= 0) {
                try {
                    int streamIndex = packet.stream_index();
                    int type = inputFormatContext.streams(streamIndex).codecpar().codec_type();
                    if (filteringContexts[streamIndex].filterGraph != null) {
                        AVFrame frame = av_frame_alloc();
                        try {

                            av_packet_rescale_ts(packet, inputFormatContext.streams(streamIndex).time_base(),
                                    streamContexts[streamIndex].decoderContext.time_base());
                            if (type == AVMEDIA_TYPE_VIDEO) {
                                check(avcodec_decode_video2(streamContexts[streamIndex].decoderContext, frame, gotFrame, packet));
                            } else {
                                check(avcodec_decode_audio4(streamContexts[streamIndex].decoderContext, frame, gotFrame, packet));
                            }
                            if (gotFrame[0] != 0) {
                                frame.pts(frame.best_effort_timestamp());
                                filterEncodeWriteFrame(frame, streamIndex);
                            }
                        } finally {
                            av_frame_free(frame);
                        }
                    } else {
                        av_packet_rescale_ts(packet, inputFormatContext.streams(streamIndex).time_base(),
                                outputFormatContext.streams(streamIndex).time_base());
                        check(av_interleaved_write_frame(outputFormatContext, packet));
                    }
                } finally {
                    av_packet_unref(packet);
                }
            }


            for (int i = 0; i < inputFormatContext.nb_streams(); i++) {
                if (filteringContexts[i].filterGraph == null) {
                    continue;
                }
                filterEncodeWriteFrame(null, i);
                flushEncoder(i);
            }

            av_write_trailer(outputFormatContext);


        } finally {
            for (int i = 0; i < inputFormatContext.nb_streams(); i++) {
                avcodec_free_context(streamContexts[i].decoderContext);
                if (outputFormatContext != null && outputFormatContext.nb_streams() > 0 &&
                        outputFormatContext.streams(i) != null && streamContexts[i].encoderContext != null) {
                    avcodec_free_context(streamContexts[i].encoderContext);
                }
                if (filteringContexts != null && filteringContexts[i].filterGraph != null) {
                    avfilter_graph_free(filteringContexts[i].filterGraph);
                }
            }
            avformat_close_input(inputFormatContext);
            if (outputFormatContext != null && (outputFormatContext.oformat().flags() & AVFMT_NOFILE) != AVFMT_NOFILE) {
                avio_closep(outputFormatContext.pb());
            }
            avformat_free_context(outputFormatContext);
        }
    }


}

$(function (){

    function startsWith(s, prefix) {
        return s && s.indexOf(prefix) === 0;
    }

    function buildSidebarLinkMap() {
        var linkMap = {};
        $('div.sphinxsidebar a.reference.internal').each(function (i,a)  {
            var href = $(a).attr('href');
            if (startsWith(href, '#module-')) {
                var id = href.substr(8);
                linkMap[id] = [$(a), null];
            }
        })
        return linkMap;
    };

    function getAdNoteDivs(dd) {
        var noteDivs = {};
        dd.find('> div.admonition.note > p.last').each(function (i, p) {
            var text = $(p).text();
            if (!noteDivs.experimental && startsWith(text, 'Experimental')) {
                noteDivs.experimental = $(p).parent();
            }
            if (!noteDivs.deprecated && startsWith(text, 'Deprecated')) {
                noteDivs.deprecated = $(p).parent();
            }
        });
        return noteDivs;
    }

    function getParentId(name) {
        var last_idx = name.lastIndexOf('.');
        return last_idx == -1? '': name.substr(0, last_idx);
    }

    function buildTag(text, cls, tooltip) {
        return '<span class="pys-tag ' + cls + ' hasTooltip">' + text + '<span class="tooltip">'
            + tooltip + '</span></span>'
    }


    var sidebarLinkMap = buildSidebarLinkMap();

    $('dl.class, dl.function').each(function (i,dl)  {

        dl = $(dl);
        dt = dl.children('dt').eq(0);
        dd = dl.children('dd').eq(0);
        var id = dt.attr('id');
        var desc = dt.find('> code.descname').text();
        var adNoteDivs = getAdNoteDivs(dd);

        if (id) {
            var parent_id = getParentId(id);

            var r = sidebarLinkMap[parent_id];
            if (r) {
                if (r[1] === null) {
                    r[1] = $('<ul/>');
                    r[0].parent().append(r[1]);
                }
                var tags = '';
                if (adNoteDivs.experimental) {
                    tags += buildTag('E', 'pys-tag-experimental', 'Experimental');
                    adNoteDivs.experimental.addClass('pys-note pys-note-experimental');
                }
                if (adNoteDivs.deprecated) {
                    tags += buildTag('D', 'pys-tag-deprecated', 'Deprecated');
                    adNoteDivs.deprecated.addClass('pys-note pys-note-deprecated');
                }
                var li = $('<li/>');
                var a = $('<a href="#' + id + '">' + desc + '</a>');
                li.append(a);
                li.append(tags);
                r[1].append(li);
                sidebarLinkMap[id] = [a, null];
            }
        }
    });
});
